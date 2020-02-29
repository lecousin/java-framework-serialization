package net.lecousin.framework.json;

import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;

import net.lecousin.framework.concurrent.Executable;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.encoding.EncodingException;
import net.lecousin.framework.encoding.HexaDecimalEncoding;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.TemporaryFiles;
import net.lecousin.framework.io.buffering.PreBufferedReadable;
import net.lecousin.framework.io.text.BufferedReadableCharacterStream;
import net.lecousin.framework.io.text.BufferedReadableCharacterStreamLocation;
import net.lecousin.framework.io.text.BufferedWritableCharacterStream;
import net.lecousin.framework.io.text.ICharacterStream;
import net.lecousin.framework.text.CharArrayStringBuffer;

/** Asynchronous JSON parser, streaming parsing events in a similar way as {@link XMLStreamEventsAsync}. */
@SuppressWarnings("squid:ClassVariableVisibilityCheck") // it is intentional, for fast access
public class JSONReaderAsync {

	/** Constructor. */
	public JSONReaderAsync(IO.Readable input, Charset encoding) {
		this(
			input instanceof IO.Readable.Buffered
				? (IO.Readable.Buffered)input
				: new PreBufferedReadable(input, 4096, input.getPriority(), 8192, input.getPriority(), 16),
			encoding);
	}
	
	/** Constructor. */
	public JSONReaderAsync(IO.Readable.Buffered input, Charset encoding) {
		this(new BufferedReadableCharacterStream(input, encoding == null ? StandardCharsets.UTF_8 : encoding, 2048, 32));
	}
	
	/** Constructor. */
	public JSONReaderAsync(ICharacterStream.Readable.Buffered input) {
		this(
			input instanceof BufferedReadableCharacterStreamLocation
				? (BufferedReadableCharacterStreamLocation)input
				: new BufferedReadableCharacterStreamLocation(input)
		);
	}
	
	/** Constructor. */
	public JSONReaderAsync(BufferedReadableCharacterStreamLocation input) {
		this.input = input;
	}
	
	private BufferedReadableCharacterStreamLocation input;
	private int maxTextSize = -1;
	
	public int getMaximumTextSize() { return maxTextSize; }
	
	public void setMaximumTextSize(int max) { maxTextSize = max; }
	
	/** Type of events. */
	public enum EventType {
		START_OBJECT,
		START_ATTRIBUTE,
		END_OBJECT,
		START_ARRAY,
		END_ARRAY,
		NULL,
		NUMBER,
		BOOLEAN,
		STRING,
		LONG_STRING
	}
	
	public EventType event = null;
	/** A BigInteger or a BigDecimal. */
	public Number number = null;
	public Boolean bool = null;
	/** Contains the attribute name with START_ATTRIBUTE, or string value with STRING event, or the numeric value as string with NUMBER event. */ 
	public CharArrayStringBuffer string = null;
	
	private FileIO.ReadWrite longStringFile = null;
	private BufferedWritableCharacterStream longStringWriter = null;
	
	/** Create a BufferedReadableCharacterStream to read the long string. */
	public BufferedReadableCharacterStream getLongString() throws IOException {
		longStringFile.seekSync(SeekType.FROM_BEGINNING, 0);
		return new BufferedReadableCharacterStream(longStringFile, StandardCharsets.UTF_8, 8192, 3);
	}
	
	/** Return the temporary file containing the long string encoded using UTF-8. */
	public FileIO.ReadWrite getLongStringFile() throws IOException {
		longStringFile.seekSync(SeekType.FROM_BEGINNING, 0);
		return longStringFile;
	}
	
	/** Read the next event.
	 * The error may be an {@link EOFException} in case the end of stream has been reached without other error.
	 */
	public Async<Exception> next() {
		event = null;
		number = null;
		bool = null;
		string = null;
		if (longStringFile != null) {
			longStringFile.closeAsync();
			longStringFile = null;
			longStringWriter = null;
		}
		Async<Exception> sp = new Async<>();
		nextChar(sp);
		return sp;
	}
	
	private enum State {
		START,
		TRUE,
		FALSE,
		NULL,
		STRING,
		NUMERIC,
		FIRST_OBJECT_ATTRIBUTE,
		OBJECT_ATTRIBUTE_NAME,
		END_OBJECT_ATTRIBUTE_NAME,
		NEXT_OBJECT_ATTRIBUTE,
		NEXT_OBJECT_ATTRIBUTE_NAME,
		FIRST_ARRAY_VALUE,
		NEXT_ARRAY_VALUE,
		END
	}
	
	private enum ContextType {
		OBJECT,
		ARRAY
	}
	
	private State state = State.START;
	private int statePos = 0;
	private LinkedList<ContextType> context = new LinkedList<>();
	
	private void nextChar(Async<Exception> sp) {
		do {
			if (State.END.equals(state)) {
				sp.error(new EOFException());
				return;
			}
			int c;
			try { c = input.readAsync(); }
			catch (IOException e) {
				sp.error(e);
				return;
			}
			if (c == -2) {
				Task.cpu("JSON Parsing", input.getPriority(), new Executable.FromRunnable(() -> nextChar(sp)))
					.startOn(input.canStartReading(), true);
				return;
			}
			boolean continu;
			switch (state) {
			case START:
				if (c == -1) {
					sp.error(new EOFException());
					return;
				}
				if (isSpaceChar((char)c)) {
					continu = true;
					break;
				}
				switch (c) {
				case '{':
					event = EventType.START_OBJECT;
					context.addFirst(ContextType.OBJECT);
					state = State.FIRST_OBJECT_ATTRIBUTE;
					sp.unblock();
					return;
				case '[':
					event = EventType.START_ARRAY;
					context.addFirst(ContextType.ARRAY);
					state = State.FIRST_ARRAY_VALUE;
					sp.unblock();
					return;
				case '"':
					state = State.STRING;
					statePos = 0; // not escaped
					string = new CharArrayStringBuffer();
					continu = true;
					break;
				case 'n':
					state = State.NULL;
					statePos = 1;
					continu = true;
					break;
				case 't':
					state = State.TRUE;
					statePos = 1;
					continu = true;
					break;
				case 'f':
					state = State.FALSE;
					statePos = 1;
					continu = true;
					break;
				default:
					if (c == '-' || (c >= '0' && c <= '9')) {
						state = State.NUMERIC;
						statePos = 0; // first digits
						string = new CharArrayStringBuffer();
						string.append((char)c);
						continu = true;
						break;
					}
					sp.error(unexpected(c, "expected is a valid JSON value (object, array, string, ...)"));
					return;
				}
				break;
			case NULL:
				continu = readNull(c, sp);
				break;
				
			case TRUE:
				continu = readTrue(c, sp);
				break;
				
			case FALSE:
				continu = readFalse(c, sp);
				break;
				
			case STRING:
				continu = readString(c, sp);
				break;
				
			case NUMERIC:
				continu = readNumeric(c, sp);
				break;
				
			case FIRST_OBJECT_ATTRIBUTE:
				continu = readFirstObjectAttribute(c, sp);
				break;
				
			case OBJECT_ATTRIBUTE_NAME:
				continu = readObjectAttributeName(c, sp);
				break;
				
			case END_OBJECT_ATTRIBUTE_NAME:
				continu = readEndObjectAttributeName(c, sp);
				break;
				
			case NEXT_OBJECT_ATTRIBUTE:
				continu = readNextObjectAttribute(c, sp);
				break;
				
			case NEXT_OBJECT_ATTRIBUTE_NAME:
				continu = readNextObjectAttributeName(c, sp);
				break;
				
			case FIRST_ARRAY_VALUE:
				continu = readFirstArrayValue(c, sp);
				break;
				
			case NEXT_ARRAY_VALUE:
				continu = readNextArrayValue(c, sp);
				break;
				
			default:
				sp.error(error("Unexpected parser state " + state));
				return;
			}
			if (!continu) return;
		} while (true);
	}
	
	private void setNextState() {
		ContextType ctx = context.peekFirst();
		if (context.isEmpty())
			state = State.END;
		else if (ContextType.OBJECT.equals(ctx))
			state = State.NEXT_OBJECT_ATTRIBUTE;
		else
			state = State.NEXT_ARRAY_VALUE;
	}

	private static final char[] ull = { 'u', 'l', 'l' };

	private boolean readNull(int c, Async<Exception> sp) {
		boolean r = expect(ull, c, sp);
		if (!r && !sp.hasError()) {
			event = EventType.NULL;
			setNextState();
			sp.unblock();
			return false;
		}
		return r;
	}

	private static final char[] rue = { 'r', 'u', 'e' };

	private boolean readTrue(int c, Async<Exception> sp) {
		boolean r = expect(rue, c, sp);
		if (!r && !sp.hasError()) {
			event = EventType.BOOLEAN;
			bool = Boolean.TRUE;
			setNextState();
			sp.unblock();
			return false;
		}
		return r;
	}

	private static final char[] alse = { 'a', 'l', 's', 'e' };
	
	private boolean readFalse(int c, Async<Exception> sp) {
		boolean r = expect(alse, c, sp);
		if (!r && !sp.hasError()) {
			event = EventType.BOOLEAN;
			bool = Boolean.FALSE;
			setNextState();
			sp.unblock();
			return false;
		}
		return r;
	}
	
	private boolean expect(char[] expected, int c, Async<Exception> sp) {
		if (c == -1) return unexpectedEnd(sp, VALID_JSON_VALUE_EXPECTED);
		if (c != expected[statePos - 1]) return unexpected(sp, c, VALID_JSON_VALUE_EXPECTED);
		if (statePos == expected.length)
			return false;
		statePos++;
		return true;
	}
	
	private boolean readString(int c, Async<Exception> sp) {
		if (c == -1) return unexpectedEnd(sp, "end of string expected");
		try {
			if (!handleString((char)c, true) && statePos == 0 && c == '"') {
				if (longStringFile != null) {
					event = EventType.LONG_STRING;
					longStringWriter.flush().block(0);
					longStringWriter = null;
				} else {
					event = EventType.STRING;
				}
				setNextState();
				sp.unblock();
				return false;
			}
		} catch (IOException e) {
			sp.error(e);
			return false;
		}
		return true;
	}
	
	private boolean handleString(char c, boolean acceptLong) throws IOException {
		if (statePos == 1) {
			// escaped
			statePos = 0;
			switch (c) {
			case 'b':
				c = '\b';
				break;
			case 'r':
				c = '\r';
				break;
			case 'n':
				c = '\n';
				break;
			case 't':
				c = '\t';
				break;
			case 'f':
				c = '\f';
				break;
			case 'u':
				statePos = 2;
				number = Long.valueOf(0);
				return true;
			default: break;
			}
			if (maxTextSize <= 0 || string.length() < maxTextSize)
				string.append(c);
			else {
				if (!acceptLong)
					throw new IOException("Too long string: " + string.asString());
				if (longStringFile == null) {
					longStringFile = TemporaryFiles.get().createAndOpenFileSync("json", "longstring");
					longStringWriter = new BufferedWritableCharacterStream(longStringFile, StandardCharsets.UTF_8, 8192);
					for (char[] chars : string.asCharacters())
						longStringWriter.writeSync(chars);
				}
				longStringWriter.writeSync(c);
			}
			return true;
		}
		if (statePos > 1) {
			// unicode hexa
			try {
				int i = HexaDecimalEncoding.decodeChar(c);
				long l = ((Long)number).longValue();
				l = (l << 4) | i;
				number = Long.valueOf(l);
				if (++statePos <= 5)
					return true;
			} catch (EncodingException e) { /* ignore */ }
			if (statePos > 2) {
				long l = ((Long)number).longValue();
				number = null;
				if (maxTextSize <= 0 || string.length() < maxTextSize)
					string.append((char)l);
				statePos = 0;
				return true;
			}
			statePos = 0;
		}
		if (c == '"')
			return false;
		if (c == '\\') {
			statePos = 1;
			return true;
		}
		if (maxTextSize <= 0 || string.length() < maxTextSize)
			string.append(c);
		return true;
	}

	private boolean readNumeric(int c, Async<Exception> sp) {
		if (c == -1)
			return endNumeric(sp);
		switch (statePos) {
		case 0:
			// first digits
			if (c == '.') {
				string.append('.');
				statePos = 1;
				return true;
			}
			if (c >= '0' && c <= '9') {
				if (maxTextSize <= 0 || string.length() < maxTextSize)
					string.append((char)c);
				return true;
			}
			input.back((char)c);
			return endNumeric(sp);
		case 1:
			// a dot has been found
			if (c >= '0' && c <= '9') {
				if (maxTextSize <= 0 || string.length() < maxTextSize)
					string.append((char)c);
				return true;
			}
			if (c == 'e' || c == 'E') {
				string.append('e');
				statePos = 2;
				return true;
			}
			input.back((char)c);
			return endNumeric(sp);
		case 2:
			// exponent
			if (c == '+' || c == '-') {
				string.append((char)c);
				statePos = 3;
				return true;
			}
			break;
		default: break;
		}
		// exponent after + or -
		if (c >= '0' && c <= '9') {
			if (maxTextSize <= 0 || string.length() < maxTextSize)
				string.append((char)c);
			return true;
		}
		input.back((char)c);
		return endNumeric(sp);
	}
	
	private boolean endNumeric(Async<Exception> sp) {
		if (statePos == 0) {
			// no dot
			try { number = new BigInteger(string.asString()); }
			catch (Exception t) {
				sp.error(error("Invalid integer value: " + string.asString()));
				return false;
			}
			event = EventType.NUMBER;
			setNextState();
			sp.unblock();
			return false;
		}
		try { number = new BigDecimal(string.asString()); }
		catch (Exception t) {
			sp.error(error("Invalid decimal value: " + string.asString()));
			return false;
		}
		event = EventType.NUMBER;
		setNextState();
		sp.unblock();
		return false;
	}
	
	private boolean readFirstObjectAttribute(int c, Async<Exception> sp) {
		if (c == -1) return unexpectedEnd(sp, "object attribute expected or character } to close the object");
		if (c == '}') {
			context.removeFirst();
			event = EventType.END_OBJECT;
			sp.unblock();
			return false;
		}
		if (c == '"') {
			state = State.OBJECT_ATTRIBUTE_NAME;
			string = new CharArrayStringBuffer();
			statePos = 0; // not escaped
			return true;
		}
		if (isSpaceChar((char)c)) return true;
		return unexpected(sp, c, "object attribute expected or character } to close the object");
	}
	
	private boolean readObjectAttributeName(int c, Async<Exception> sp) {
		if (c == -1) return unexpectedEnd(sp, "object attribute name expected");
		try {
			if (!handleString((char)c, false) && statePos == 0 && c == '"') {
				state = State.END_OBJECT_ATTRIBUTE_NAME;
				return true;
			}
		} catch (IOException e) {
			sp.error(e);
			return false;
		}
		return true;
	}
	
	private boolean readEndObjectAttributeName(int c, Async<Exception> sp) {
		if (c == -1) return unexpectedEnd(sp, "character ':' expected after object attribute name");
		if (c == ':') {
			event = EventType.START_ATTRIBUTE;
			state = State.START;
			sp.unblock();
			return false;
		}
		if (isSpaceChar((char)c)) return true;
		return unexpected(sp, c, "character ':' expected after object attribute name");
	}
	
	private boolean readNextObjectAttribute(int c, Async<Exception> sp) {
		if (c == -1) return unexpectedEnd(sp, "expected is character , for a new attribute or } to close the object");
		if (c == '}') {
			context.removeFirst();
			event = EventType.END_OBJECT;
			setNextState();
			sp.unblock();
			return false;
		}
		if (c == ',') {
			state = State.NEXT_OBJECT_ATTRIBUTE_NAME;
			return true;
		}
		if (isSpaceChar((char)c)) return true;
		return unexpected(sp, c, "expected is character , for a new attribute or } to close the object");
	}
	
	private boolean readNextObjectAttributeName(int c, Async<Exception> sp) {
		if (c == -1) return unexpectedEnd(sp, "object attribute expected");
		if (c == '"') {
			state = State.OBJECT_ATTRIBUTE_NAME;
			string = new CharArrayStringBuffer();
			statePos = 0; // not escaped
			return true;
		}
		if (isSpaceChar((char)c)) return true;
		return unexpected(sp, c, "object attribute expected");
	}	
	
	private boolean readFirstArrayValue(int c, Async<Exception> sp) {
		if (c == -1) return unexpectedEnd(sp, "expected a value or ] to close the array");
		if (c == ']') {
			context.removeFirst();
			event = EventType.END_ARRAY;
			setNextState();
			sp.unblock();
			return false;
		}
		if (isSpaceChar((char)c)) return true;
		state = State.START;
		input.back((char)c);
		return true;
	}
	
	private boolean readNextArrayValue(int c, Async<Exception> sp) {
		if (c == -1) return unexpectedEnd(sp, "expected is character , for a new value or ] to close the array");
		if (c == ']') {
			context.removeFirst();
			event = EventType.END_ARRAY;
			setNextState();
			sp.unblock();
			return false;
		}
		if (c == ',') {
			state = State.START;
			return true;
		}
		if (isSpaceChar((char)c)) return true;
		return unexpected(sp, c, "expected is character , for a new value or ] to close the array");
	}
	
	private static boolean isSpaceChar(char c) {
		return c == ' ' || c == '\t' || c == '\r' || c == '\n';
	}
	
	/** Creates a JSONParsingException. */
	public JSONParsingException error(String message) {
		String loc = input.getDescription();
		StringBuilder s = new StringBuilder(loc.length() + message.length() + 10);
		s.append(loc).append(':').append(input.getLine()).append(',').append(input.getPositionInLine()).append(':').append(message);
		return new JSONParsingException(s.toString());
	}
	
	private JSONParsingException unexpected(int c, String message) {
		return error("Unexpected character '" + ((char)c) + "', " + message);
	}
	
	private boolean unexpected(Async<Exception> sp, int c, String message) {
		sp.error(unexpected(c, message));
		return false;
	}
	
	private JSONParsingException unexpectedEnd(String context) {
		return error("Unexpected end, " + context);
	}
	
	private boolean unexpectedEnd(Async<Exception> sp, String context) {
		sp.error(unexpectedEnd(context));
		return false;
	}
	
	private static final String VALID_JSON_VALUE_EXPECTED = "a valid JSON value is expected";
	
}
