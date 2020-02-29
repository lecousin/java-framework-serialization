package net.lecousin.framework.json;

import java.io.EOFException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.collections.LinkedArrayList;
import net.lecousin.framework.concurrent.Executable;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.encoding.number.HexadecimalNumber;
import net.lecousin.framework.io.text.ICharacterStream.Readable.Buffered;
import net.lecousin.framework.text.CharArrayString;
import net.lecousin.framework.text.CharArrayStringBuffer;

/**
 * Parse a JSON value from a character stream.<br/>
 * <br/>
 * The returned object may be one of:<ul>
 * 	<li>A Map&lt;String, Object&gt; for an object structure</li>
 * 	<li>A List&lt;Object&gt; for an array</li>
 * 	<li>A String</li>
 * 	<li>A Number</li>
 * 	<li>A Boolean</li>
 * 	<li>null</li>
 * </ul>
 * Any spaces at the beginning of the stream are ignored. Only the first value is parsed, if there
 * are remaining characters in the stream, they are ignored.<br/>
 * <br/>
 * Exceptions that may be raised are:<ul>
 * 	<li>IOException in case of error while reading from the character stream,
 * 		including an EOFException if the end of the stream is reached in the middle of the JSON structure</li>
 * 	<li>JSONParsingException in case the JSON cannot be parsed</li>
 * </ul>
 */
public class JSONParser implements Executable<Object, Exception> {

	/** Utility method to parse a JSON structure. */
	public static AsyncSupplier<Object, Exception> parse(Buffered io, Priority priority) {
		JSONParser p = new JSONParser(io);
		Task<Object, Exception> task = Task.cpu("JSONParser", priority, p);
		task.startOn(io.canStartReading(), false);
		return task.getOutput();
	}
	
	/** Constructor. */
	public JSONParser(Buffered io) {
		this.io = io;
	}
	
	private Buffered io;
	
	@Override
	public Object execute(Task<Object, Exception> taskContext) throws Exception {
		return read(CTX_JSON);
	}
	
	private static final String CTX_JSON = "JSON";
	private static final String CTX_ARRAY = "JSON array";
	private static final String CTX_OBJECT = "JSON object";
	
	private Object read(String context) throws IOException, JSONParsingException {
		char c = skipSpaces();
		switch (c) {
		case '{': return readObject();
		case '[': return readArray();
		case '"': return readString();
		case '-': return readNumber(true, 0);
		case 't':
			if (!readTrue())
				throw JSONParsingException.unexpectedCharacter(c, context);
			return Boolean.TRUE;
		case 'f':
			if (!readFalse())
				throw JSONParsingException.unexpectedCharacter(c, context);
			return Boolean.FALSE;
		case 'n':
			if (!readNull())
				throw JSONParsingException.unexpectedCharacter(c, context);
			return null;
		default:
			if (c >= '0' && c <= '9')
				return readNumber(false, ((long)c) - '0');
			throw JSONParsingException.unexpectedCharacter(c, context);
		}
	}
	
	public static boolean isSpaceChar(char c) {
		if (c == 32) return true;
		if (c < 9 || c > 13) return false;
		if (c == 11 || c == 12) return false;
		return true;
	}
	
	private char skipSpaces() throws IOException {
		char c;
		while (isSpaceChar(c = io.read()));
		return c;
	}
	
	private List<Object> readArray() throws IOException, JSONParsingException {
		LinkedArrayList<Object> array = new LinkedArrayList<>(10);
		do {
			array.add(read(CTX_ARRAY));
			char c = skipSpaces();
			if (c == ',')
				continue;
			if (c == ']')
				return array;
			throw JSONParsingException.unexpectedCharacter(c, CTX_ARRAY);
		} while (true);
	}
	
	private Map<String,Object> readObject() throws IOException, JSONParsingException {
		Map<String,Object> map = new HashMap<>();
		boolean first = true;
		do {
			char c = skipSpaces();
			if (c == '}')
				return map;
			if (!first) {
				if (c != ',')
					throw JSONParsingException.unexpectedCharacter(c, CTX_OBJECT);
				c = skipSpaces();
			}
			if (c != '"')
				throw JSONParsingException.unexpectedCharacter(c, CTX_OBJECT);
			String name = readString();
			c = skipSpaces();
			if (c != ':')
				throw JSONParsingException.unexpectedCharacter(c, CTX_OBJECT);
			Object val = read(CTX_OBJECT);
			map.put(name, val);
			first = false;
		} while (true);
	}
	
	private String readString() throws IOException, JSONParsingException {
		CharArrayStringBuffer s = new CharArrayStringBuffer(new CharArrayString(16));
		do {
			char c = io.read();
			switch (c) {
			case '"': return s.toString();
			case '\\':
				c = io.read();
				if (c == '"' || c == '\\' || c == '/')
					s.append(c);
				else if (c == 'b') s.append('\b');
				else if (c == 'f') s.append('\f');
				else if (c == 'n') s.append('\n');
				else if (c == 'r') s.append('\r');
				else if (c == 't') s.append('\t');
				else if (c == 'u') {
					HexadecimalNumber h = new HexadecimalNumber();
					for (int i = 0; i < 4; ++i)
						if (!h.addChar(io.read()))
							throw new JSONParsingException("Invalid hexadecimal character");
					s.append((char)h.getNumber());
				} else {
					s.append(c);
				}
				break;
			default:
				s.append(c);
			}
		} while (true);
	}
	
	private Number readNumber(boolean negative, long value) throws IOException {
		char c;
		do {
			c = io.read();
			if (c >= '0' && c <= '9') {
				value *= 10;
				value += c - '0';
				continue;
			}
			if (c == '.')
				break;
			if (c == 'e' || c == 'E')
				return readNumberExp(negative, value, c);
			io.back(c);
			return Long.valueOf(negative ? -value : value);
		} while (true);
		StringBuilder s = new StringBuilder();
		if (negative) s.append('-');
		s.append(value);
		s.append('.');
		boolean e = false;
		do {
			try { c = io.read(); }
			catch (EOFException ex) { break; }
			if (c >= '0' && c <= '9')
				s.append(c);
			else if (!e && (c == 'e' || c == 'E')) {
				e = true;
				s.append(c);
				c = io.read();
				s.append(c);
				if (c == '+' || c == '-') {
					c = io.read();
					s.append(c);
				}
			} else {
				io.back(c);
				break;
			}
		} while (true);
		return new Double(s.toString());
	}
	
	private Double readNumberExp(boolean negative, long value, char c) throws IOException {
		StringBuilder s = new StringBuilder();
		if (negative) s.append('-');
		s.append(value);
		s.append(c);
		c = io.read();
		s.append(c);
		if (c == '+' || c == '-') {
			c = io.read();
			s.append(c);
		}
		do {
			try { c = io.read(); }
			catch (EOFException e) { break; }
			if (c >= '0' && c <= '9')
				s.append(c);
			else {
				io.back(c);
				break;
			}
		} while (true);
		return new Double(s.toString());
	}
	
	private char[] c4 = new char[4];
	
	private boolean readTrue() throws IOException {
		if (io.readFullySync(c4, 0, 3) != 3) return false;
		return c4[0] == 'r' && c4[1] == 'u' && c4[2] == 'e';
	}
	
	private boolean readFalse() throws IOException {
		if (io.readFullySync(c4, 0, 4) != 4) return false;
		return c4[0] == 'a' && c4[1] == 'l' && c4[2] == 's' && c4[3] == 'e';
	}
	
	private boolean readNull() throws IOException {
		if (io.readFullySync(c4, 0, 3) != 3) return false;
		return c4[0] == 'u' && c4[1] == 'l' && c4[2] == 'l';
	}

}
