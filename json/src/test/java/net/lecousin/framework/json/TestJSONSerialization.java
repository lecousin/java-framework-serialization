package net.lecousin.framework.json;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.core.test.runners.LCConcurrentRunner;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOAsInputStream;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.SimpleBufferedWritable;
import net.lecousin.framework.io.buffering.SingleBufferReadable;
import net.lecousin.framework.io.serialization.Deserializer;
import net.lecousin.framework.io.serialization.SerializationException;
import net.lecousin.framework.io.serialization.SerializationSpecWriter;
import net.lecousin.framework.io.serialization.Serializer;
import net.lecousin.framework.serialization.test.TestSerialization;

import org.everit.json.schema.Schema;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameters;

@RunWith(LCConcurrentRunner.Parameterized.class) @org.junit.runners.Parameterized.UseParametersRunnerFactory(LCConcurrentRunner.ConcurrentParameterizedRunnedFactory.class)
public class TestJSONSerialization extends TestSerialization {

	@Parameters(name = "efficient = {0}")
	public static Collection<Object[]> parameters() {
		return Arrays.asList(new Object[] { Boolean.TRUE }, new Object[] { Boolean.FALSE });
	}
	
	public TestJSONSerialization(boolean efficient) {
		this.efficient = efficient;
	}
	
	protected boolean efficient;
	
	@Override
	protected Serializer createSerializer() {
		if (efficient)
			return new JSONSerializer();
		return new JSONSerializer() {
			@Override
			protected IAsync<SerializationException> initializeSerialization(IO.Writable output) {
				bout = new SimpleBufferedWritable(output, 5);
				this.output = new JSONWriter(bout, encoding, pretty);
				return new Async<>(bout.canStartWriting(), e -> new SerializationException("Error initializing JSON serialization", e));
			}
		};
	}
	
	@Override
	protected Deserializer createDeserializer() {
		if (efficient) {
			JSONDeserializer d = new JSONDeserializer();
			d.setMaximumTextSize(1024);
			return d;
		}
		return new JSONDeserializer() {
			@Override
			protected IAsync<SerializationException> initializeDeserialization(IO.Readable input) {
				this.input = new JSONReaderAsync(new SingleBufferReadable(input, 2, true), encoding);
				return new Async<>(true);
			}
		};
	}
	
	@Override
	protected SerializationSpecWriter createSpecWriter() {
		// TODO take into account efficient
		return new JSONSpecWriter();
	}
	
	@Override
	protected void checkSpec(IO.Readable.Seekable spec, Class<?> type, IO.Readable.Seekable serialization) throws Exception {
		serialization.seekSync(SeekType.FROM_BEGINNING, 0);
		String content = IOUtil.readFullyAsStringSync(serialization, StandardCharsets.UTF_8);
		serialization.seekSync(SeekType.FROM_BEGINNING, 0);
		// TODO null value for enum not supported by validator in current version (already fixed on github repo)
		if (type.isEnum() && "null".equals(content))
			return;
		if (type.equals(TestSimpleObjects.class))
			return;

		spec.seekSync(SeekType.FROM_BEGINNING, 0);
		JSONObject rawSchema = new JSONObject(new JSONTokener(IOAsInputStream.get(spec, false)));
		spec.seekSync(SeekType.FROM_BEGINNING, 0);

		Schema schema = SchemaLoader.load(rawSchema);
		schema.validate(new JSONTokener(content).nextValue());
	}
}
