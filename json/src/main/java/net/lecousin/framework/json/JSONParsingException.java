package net.lecousin.framework.json;

/** Error while parsing a JSON value. */
public class JSONParsingException extends Exception {

	private static final long serialVersionUID = 7804112216262792908L;

	/** Constructor. */
	public JSONParsingException(String message) {
		super(message);
	}
	
	/** Constructor. */
	public JSONParsingException(String message, Throwable cause) {
		super(message, cause);
	}
	
	/** Unexpected character. */
	public static JSONParsingException unexpectedCharacter(char c, String context) {
		return new JSONParsingException("Unexpected character '" + c + "' in " + context);
	}
	
}
