package net.lecousin.framework.json;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.encoding.HexaDecimalEncoding;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.text.BufferedWritableCharacterStream;
import net.lecousin.framework.io.text.CharacterStreamWritePool;
import net.lecousin.framework.io.text.ICharacterStream;

/** Generates a JSON structure. */
public class JSONWriter {

	/** Constructor. */
	public JSONWriter(IO.Writable.Buffered output, boolean pretty) {
		this(output, null, pretty);
	}

	/** Constructor. */
	public JSONWriter(IO.Writable.Buffered output, Charset encoding, boolean pretty) {
		this(new BufferedWritableCharacterStream(output, encoding != null ? encoding : StandardCharsets.UTF_8, 4096), pretty);
	}
	
	/** Constructor. */
	public JSONWriter(ICharacterStream.Writable.Buffered output, boolean pretty) {
		this.pretty = pretty;
		writer = new CharacterStreamWritePool(output);
	}
	
	private boolean pretty;
	private int indent = 0;
	private CharacterStreamWritePool writer;
	private LinkedList<Boolean> first = new LinkedList<>();
	
	private static final char[] NULL = new char[] { 'n', 'u', 'l', 'l' };
	private static final char[] TRUE = new char[] { 't', 'r', 'u', 'e' };
	private static final char[] FALSE = new char[] { 'f', 'a', 'l', 's', 'e' };
	
	/** Escape a string to be written in JSON. */
	public static String escape(CharSequence s) {
		int len = s.length();
		StringBuilder str = new StringBuilder(len);
		char c2 = 0;
		for (int i = 0; i < len; ++i) {
			char c = s.charAt(i);
			switch (c) {
			case '\b':
				c2 = 'b';
				break;
			case '\r':
				c2 = 'r';
				break;
			case '\n':
				c2 = 'n';
				break;
			case '\f':
				c2 = 'f';
				break;
			case '\t':
				c2 = 't';
				break;
			case '\\':
				c2 = c;
				break;
			case '"':
				c2 = c;
				break;
			default:
				if (c < 32 || c > 127)
					str.append("\\u")
						.append(HexaDecimalEncoding.encodeDigit((c & 0xF000) >> 12))
						.append(HexaDecimalEncoding.encodeDigit((c & 0x0F00) >> 8))
						.append(HexaDecimalEncoding.encodeDigit((c & 0x00F0) >> 4))
						.append(HexaDecimalEncoding.encodeDigit(c & 0x000F));
				else str.append(c);
				continue;
			}
			str.append('\\');
			str.append(c2);
		}
		return str.toString();
	}

	/** Flush any pending output. */
	public IAsync<IOException> flush() {
		return writer.flush();
	}
	
	private IAsync<IOException> indent(IAsync<IOException> last) {
		if (last == null)
			last = new Async<>(true);
		for (int i = 0; i < indent; ++i)
			last = writer.write('\t');
		return last;
	}
	
	/** Open a JSON object ({). */
	public IAsync<IOException> openObject() {
		first.addFirst(Boolean.TRUE);
		if (pretty) {
			writer.write('{');
			indent++;
			return indent(writer.write('\n'));
		}
		return writer.write('{');
	}
	
	private static final String ERROR_OBJECT_NOT_OPEN = "Object not open";
	
	/** Close a JSON object (}). */
	public IAsync<IOException> closeObject() {
		if (first.pollFirst() == null)
			return new Async<>(new IOException(ERROR_OBJECT_NOT_OPEN));
		if (pretty) {
			writer.write('\n');
			indent--;
			indent(null);
		}
		return writer.write('}');
	}
	
	/** Start a new object attribute, with its name followed by a semicolon. */
	public IAsync<IOException> addObjectAttribute(String name) {
		Boolean b = first.peekFirst();
		if (b == null)
			return new Async<>(new IOException(ERROR_OBJECT_NOT_OPEN));
		if (b.booleanValue())
			first.set(0, Boolean.FALSE);
		else
			writer.write(',');
		if (pretty) {
			writer.write('\n');
			indent(null);
		}
		writer.write('"');
		writer.write(escape(name));
		writer.write('"');
		return writer.write(':');
	}
	
	/** Add a boolean attribute to the current object. Equivalent to addObjectAttribute then writeBoolean. */
	public IAsync<IOException> addObjectAttribute(String name, boolean value) {
		addObjectAttribute(name);
		return writeBoolean(value);
	}
	
	/** Add a number attribute to the current object. Equivalent to addObjectAttribute then writeNumber. */
	public IAsync<IOException> addObjectAttribute(String name, Number value) {
		addObjectAttribute(name);
		return writeNumber(value);
	}
	
	/** Add a string attribute to the current object. Equivalent to addObjectAttribute then writeString. */
	public IAsync<IOException> addObjectAttribute(String name, String value) {
		addObjectAttribute(name);
		return writeString(value);
	}
	
	/** Open an array ([). */
	public IAsync<IOException> openArray() {
		first.addFirst(Boolean.TRUE);
		if (pretty) {
			writer.write('[');
			indent++;
			return indent(writer.write('\n'));
		}
		return writer.write('[');
	}
	
	/** Close an array (]). */
	public IAsync<IOException> closeArray() {
		if (first.pollFirst() == null)
			return new Async<>(new IOException("Array not open"));
		if (pretty) {
			indent--;
			writer.write('\n');
			indent(null);
			return writer.write(']');
		}
		return writer.write(']');
	}
	
	/** Start a new element in an array, writing a comma if a previous element is present. */
	public IAsync<IOException> startNextArrayElement() {
		Boolean b = first.peekFirst();
		if (b == null)
			return new Async<>(new IOException(ERROR_OBJECT_NOT_OPEN));
		if (b.booleanValue()) {
			first.set(0, Boolean.FALSE);
			return new Async<>(true);
		}
		if (pretty) {
			writer.write(',');
			return indent(writer.write('\n'));
		}
		return writer.write(',');
	}
	
	/** Write null. */
	public IAsync<IOException> writeNull() {
		return writer.write(NULL);
	}
	
	/** Write a string between double quotes. */
	public IAsync<IOException> writeString(CharSequence s) {
		writer.write('"');
		writer.write(escape(s));
		return writer.write('"');
	}
	
	/** Start a string by opening double quotes. */
	public IAsync<IOException> startString() {
		return writer.write('"');
	}

	/** Write a part of a string content. */
	public IAsync<IOException> writeStringContent(CharSequence s) {
		return writer.write(escape(s));
	}

	/** Write a part of a string content. */
	public IAsync<IOException> writeEscapedStringContent(CharSequence s) {
		return writer.write(s);
	}
	
	/** End a string by closing double quotes. */
	public IAsync<IOException> endString() {
		return writer.write('"');
	}
	
	/** Write a number. */
	public IAsync<IOException> writeNumber(Number n) {
		return writer.write(n.toString());
	}
	
	/** Write a boolean. */
	public IAsync<IOException> writeBoolean(boolean b) {
		return writer.write(b ? TRUE : FALSE);
	}
	
}
