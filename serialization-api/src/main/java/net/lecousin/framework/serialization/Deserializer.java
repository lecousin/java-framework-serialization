package net.lecousin.framework.serialization;

import java.util.List;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.serialization.rules.SerializationRule;

/** Perform deserialization. */
public interface Deserializer {

	/** Return the maximum text size, 0 or negative value means no limitation. */
	int getMaximumTextSize();
	
	/** Set the maximum text size, 0 or negative value means no limitation. */
	void setMaximumTextSize(int max);
	
	/** Deserialize an object/value of the given type from the given input. */
	<T> AsyncSupplier<T, SerializationException> deserialize(TypeDefinition type, IO.Readable input, List<SerializationRule> rules);
	
	
	/** Allow to handle stream with external reference. */
	static interface StreamReferenceHandler {
		/** Return true if the text found is a reference. */
		boolean isReference(String text);
		
		/** Retrieve the stream from the given reference. */
		AsyncSupplier<IO.Readable, SerializationException> getStreamFromReference(String text);
	}

	/** Register a stream reference handler. */
	void addStreamReferenceHandler(StreamReferenceHandler handler);
	
}
