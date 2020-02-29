package net.lecousin.framework.serialization;

import java.util.List;

import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.serialization.rules.SerializationRule;

/** Write the specification for (de)serialization. */
public interface SerializationSpecWriter {

	/** Write the specification for the given type. */
	IAsync<SerializationException> writeSpecification(Class<?> type, IO.Writable output, List<SerializationRule> rules);
	
}
