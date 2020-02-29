package net.lecousin.framework.json;

import net.lecousin.framework.io.serialization.SerializationException;

/** Error during JSON Deserialization. */
public class JSONDeserializationException extends SerializationException {

	private static final long serialVersionUID = 1L;

	/** Constructor. */
	public JSONDeserializationException(String message) {
		super(message);
	}
	
	/** Constructor. */
	public JSONDeserializationException(String message, Throwable cause) {
		super(message, cause);
	}
	
	/** Error reading JSON. */
	public static JSONDeserializationException errorReadingJSON(Throwable cause) {
		return new JSONDeserializationException("Error reading JSON", cause);
	}
	
	/** Error instantiating a type. */
	public static JSONDeserializationException instantiationError(Throwable cause) {
		return new JSONDeserializationException("Error instantiating type", cause);
	}

	/** Unexpected thing found. */
	public static JSONDeserializationException unexpected(String found, String expected) {
		return new JSONDeserializationException("Unexpected " + found + ", expected was: " + expected);
	}
	
	/** Unexpected null found. */
	public static JSONDeserializationException unexpectedNull(String expected) {
		return unexpected("null value", expected);
	}
	
	/** Unexpected value found. */
	public static JSONDeserializationException unexpectedValue(String expected) {
		return unexpected("value", expected);
	}
}
