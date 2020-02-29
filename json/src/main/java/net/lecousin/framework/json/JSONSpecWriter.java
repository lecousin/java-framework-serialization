package net.lecousin.framework.json;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.function.Function;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.buffering.SimpleBufferedWritable;
import net.lecousin.framework.io.serialization.AbstractSerializationSpecWriter;
import net.lecousin.framework.io.serialization.SerializationContext;
import net.lecousin.framework.io.serialization.SerializationContext.AttributeContext;
import net.lecousin.framework.io.serialization.SerializationContext.CollectionContext;
import net.lecousin.framework.io.serialization.SerializationContext.ObjectContext;
import net.lecousin.framework.io.serialization.SerializationException;
import net.lecousin.framework.io.serialization.TypeDefinition;
import net.lecousin.framework.io.serialization.rules.SerializationRule;

/** Generate JSON schema (http://json-schema.org/). */
public class JSONSpecWriter extends AbstractSerializationSpecWriter {

	/** Constructor. */
	public JSONSpecWriter() {
		this(false);
	}

	/** Constructor. */
	public JSONSpecWriter(boolean pretty) {
		this(null, pretty);
	}

	/** Constructor. */
	public JSONSpecWriter(Charset encoding) {
		this(encoding, 4096, false);
	}

	/** Constructor. */
	public JSONSpecWriter(Charset encoding, boolean pretty) {
		this(encoding, 4096, pretty);
	}
	
	/** Constructor. */
	public JSONSpecWriter(Charset encoding, int bufferSize) {
		this(encoding, bufferSize, false);
	}

	/** Constructor. */
	public JSONSpecWriter(Charset encoding, int bufferSize, boolean pretty) {
		this.encoding = encoding;
		this.bufferSize = bufferSize;
		this.pretty = pretty;
	}
	
	/** Constructo. */
	public JSONSpecWriter(JSONWriter output) {
		this.output = output;
	}
	
	protected Charset encoding;
	protected int bufferSize;
	protected boolean pretty;
	protected IO.Writable.Buffered bout;
	protected JSONWriter output;
	
	private static final Function<IOException, SerializationException> ioErrorConverter =
		e -> new SerializationException("Error writing JSON schema", e);
		
	private static final String TYPE_NULL = "null";
	private static final String TYPE_BOOLEAN = "boolean";
	private static final String TYPE_NUMBER = "number";
	private static final String TYPE_STRING = "string";
	private static final String TYPE_ARRAY = "array";
	private static final String TYPE_OBJECT = "object";
	
	@Override
	protected IAsync<SerializationException> initializeSpecWriter(IO.Writable output) {
		if (output instanceof IO.Writable.Buffered)
			bout = (IO.Writable.Buffered)output;
		else
			bout = new SimpleBufferedWritable(output, bufferSize);
		this.output = new JSONWriter(bout, encoding, pretty);
		return new Async<>(this.output.openObject(), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> finalizeSpecWriter() {
		Async<IOException> sp = new Async<>();
		output.closeObject().onDone(() -> output.flush().onDone(() -> bout.flush().onDone(sp), sp), sp);
		return new Async<>(sp, ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> specifyBooleanValue(SerializationContext context, boolean nullable) {
		output.addObjectAttribute("type");
		if (!nullable)
			return new Async<>(output.writeString(TYPE_BOOLEAN), ioErrorConverter);
		output.openArray();
		output.startNextArrayElement();
		output.writeString(TYPE_BOOLEAN);
		output.startNextArrayElement();
		output.writeString(TYPE_NULL);
		return new Async<>(output.closeArray(), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> specifyNumericValue(
		SerializationContext context, Class<?> type, boolean nullable, Number min, Number max
	) {
		IAsync<IOException> result;
		output.addObjectAttribute("type");
		if (!nullable)
			result = output.writeString(TYPE_NUMBER);
		else {
			output.openArray();
			output.startNextArrayElement();
			output.writeString(TYPE_NUMBER);
			output.startNextArrayElement();
			output.writeString(TYPE_NULL);
			result = output.closeArray();
		}
		if (min != null) {
			output.addObjectAttribute("minimum");
			result = output.writeNumber(min);
		}
		if (max != null) {
			output.addObjectAttribute("maximum");
			result = output.writeNumber(max);
		}
		return new Async<>(result, ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> specifyCharacterValue(SerializationContext context, boolean nullable) {
		output.addObjectAttribute("type");
		if (!nullable)
			output.writeString(TYPE_STRING);
		else {
			output.openArray();
			output.startNextArrayElement();
			output.writeString(TYPE_STRING);
			output.startNextArrayElement();
			output.writeString(TYPE_NULL);
			output.closeArray();
		}
		
		output.addObjectAttribute("minLength");
		output.writeNumber(Integer.valueOf(nullable ? 0 : 1));
		output.addObjectAttribute("maxLength");
		return new Async<>(output.writeNumber(Integer.valueOf(1)), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> specifyStringValue(SerializationContext context, TypeDefinition type) {
		output.addObjectAttribute("type");
		output.openArray();
		output.startNextArrayElement();
		output.writeString(TYPE_STRING);
		output.startNextArrayElement();
		output.writeString(TYPE_NULL);
		return new Async<>(output.closeArray(), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> specifyEnumValue(SerializationContext context, TypeDefinition type) {
		output.addObjectAttribute("type");
		output.openArray();
		output.startNextArrayElement();
		output.writeString(TYPE_STRING);
		output.startNextArrayElement();
		output.writeString(TYPE_NULL);
		output.closeArray();
		
		output.addObjectAttribute("enum");
		output.openArray();
		try {
			output.startNextArrayElement();
			output.writeNull();
			Enum<?>[] values = (Enum<?>[])type.getBase().getMethod("values").invoke(null);
			for (int i = 0; i < values.length; ++i) {
				output.startNextArrayElement();
				output.writeString(values[i].name());
			}
		} catch (Exception t) {
			/* should not happen */
		}
		return new Async<>(output.closeArray(), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> specifyCollectionValue(CollectionContext context, List<SerializationRule> rules) {
		output.addObjectAttribute("type");
		output.openArray();
		output.startNextArrayElement();
		output.writeString(TYPE_ARRAY);
		output.startNextArrayElement();
		output.writeString(TYPE_NULL);
		output.closeArray();
		
		output.addObjectAttribute("items");
		output.openObject();
		IAsync<SerializationException> element = specifyValue(context, context.getElementType(), rules);
		if (element.isDone()) {
			if (element.hasError()) return element;
			return new Async<>(output.closeObject(), ioErrorConverter);
		}
		Async<SerializationException> sp = new Async<>();
		element.thenStart(taskDescription, priority, () -> output.closeObject().onDone(sp, ioErrorConverter), sp);
		return sp;
	}
	
	@Override
	protected IAsync<SerializationException> specifyTypedValue(ObjectContext context, List<SerializationRule> rules) {
		output.addObjectAttribute("type");
		output.openArray();
		output.startNextArrayElement();
		output.writeString(TYPE_OBJECT);
		output.startNextArrayElement();
		output.writeString(TYPE_NULL);
		output.closeArray();
		
		output.addObjectAttribute("properties");
		output.openObject();
		IAsync<SerializationException> content = specifyTypeContent(context, rules);
		if (content.isDone()) {
			if (content.hasError()) return content;
			return new Async<>(output.closeObject(), ioErrorConverter);
		}
		Async<SerializationException> sp = new Async<>();
		content.thenStart(taskDescription, priority, () -> output.closeObject().onDone(sp, ioErrorConverter), sp);
		return sp;
	}
	
	@Override
	protected IAsync<SerializationException> specifyTypeAttribute(AttributeContext context, List<SerializationRule> rules) {
		output.addObjectAttribute(context.getAttribute().getName());
		output.openObject();
		IAsync<SerializationException> spec = specifyValue(context, context.getAttribute().getType(), rules);
		if (spec.isDone()) {
			if (spec.hasError()) return spec;
			return new Async<>(output.closeObject(), ioErrorConverter);
		}
		Async<SerializationException> sp = new Async<>();
		spec.thenStart(taskDescription, priority, () -> output.closeObject().onDone(sp, ioErrorConverter), sp);
		return sp;
	}
	
	@Override
	protected IAsync<SerializationException> specifyIOReadableValue(SerializationContext context, List<SerializationRule> rules) {
		return specifyStringValue(context, null);
	}
	
	@Override
	protected IAsync<SerializationException> specifyAnyValue(SerializationContext context) {
		output.addObjectAttribute("type");
		output.openArray();
		output.startNextArrayElement();
		output.writeString(TYPE_NULL);
		output.startNextArrayElement();
		output.writeString(TYPE_BOOLEAN);
		output.startNextArrayElement();
		output.writeString(TYPE_OBJECT);
		output.startNextArrayElement();
		output.writeString(TYPE_ARRAY);
		output.startNextArrayElement();
		output.writeString(TYPE_NUMBER);
		output.startNextArrayElement();
		output.writeString(TYPE_STRING);
		return new Async<>(output.closeArray(), ioErrorConverter);
	}
	
}
