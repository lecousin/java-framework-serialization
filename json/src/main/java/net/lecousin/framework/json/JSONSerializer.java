package net.lecousin.framework.json;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import net.lecousin.framework.concurrent.Executable;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.encoding.Base64Encoding;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.buffering.SimpleBufferedWritable;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.io.data.Bytes;
import net.lecousin.framework.io.serialization.AbstractSerializer;
import net.lecousin.framework.io.serialization.SerializationClass.Attribute;
import net.lecousin.framework.io.serialization.SerializationContext;
import net.lecousin.framework.io.serialization.SerializationContext.AttributeContext;
import net.lecousin.framework.io.serialization.SerializationContext.CollectionContext;
import net.lecousin.framework.io.serialization.SerializationContext.ObjectContext;
import net.lecousin.framework.io.serialization.SerializationException;
import net.lecousin.framework.io.serialization.SerializationUtil;
import net.lecousin.framework.io.serialization.rules.SerializationRule;
import net.lecousin.framework.text.ByteArrayStringIso8859;
import net.lecousin.framework.text.CharArrayString;

/** Serialize an object into JSON. */
public class JSONSerializer extends AbstractSerializer {

	/** Constructor. */
	public JSONSerializer() {
		this(StandardCharsets.UTF_8, 4096, false);
	}

	/** Constructor. */
	public JSONSerializer(Charset encoding, boolean pretty) {
		this(encoding, 4096, pretty);
	}

	/** Constructor. */
	public JSONSerializer(Charset encoding, int bufferSize, boolean pretty) {
		this.encoding = encoding;
		this.bufferSize = bufferSize;
		this.pretty = pretty;
	}
	
	protected Charset encoding;
	protected int bufferSize;
	protected boolean pretty;
	protected IO.Writable.Buffered bout;
	protected JSONWriter output;
	
	private static final Function<IOException, SerializationException> ioErrorConverter =
		e -> new SerializationException("Error writing JSON", e);
	
	@Override
	protected IAsync<SerializationException> initializeSerialization(IO.Writable output) {
		if (output instanceof IO.Writable.Buffered)
			bout = (IO.Writable.Buffered)output;
		else
			bout = new SimpleBufferedWritable(output, bufferSize);
		this.output = new JSONWriter(bout, encoding, pretty);
		return new Async<>(bout.canStartWriting(), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> finalizeSerialization() {
		Async<SerializationException> sp = new Async<>();
		output.flush().onDone(() -> bout.flush().onDone(sp, ioErrorConverter), sp, ioErrorConverter);
		return sp;
	}
	
	@Override
	protected IAsync<SerializationException> serializeBooleanValue(boolean value) {
		return new Async<>(output.writeBoolean(value), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> serializeNullValue() {
		return new Async<>(output.writeNull(), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> serializeCharacterValue(char value) {
		return new Async<>(output.writeString(new String(new char[] { value })), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> serializeNumericValue(Number value) {
		return new Async<>(output.writeNumber(value), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> serializeStringValue(CharSequence value) {
		return new Async<>(output.writeString(value), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> startCollectionValue(
		CollectionContext context, String path, List<SerializationRule> rules
	) {
		return new Async<>(output.openArray(), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> startCollectionValueElement(
		CollectionContext context, Object element, int elementIndex, String elementPath, List<SerializationRule> rules
	) {
		return new Async<>(output.startNextArrayElement(), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> endCollectionValueElement(
		CollectionContext context, Object element, int elementIndex, String elementPath, List<SerializationRule> rules
	) {
		return new Async<>(true);
	}
	
	@Override
	protected IAsync<SerializationException> endCollectionValue(
		CollectionContext context, String path, List<SerializationRule> rules
	) {
		return new Async<>(output.closeArray(), ioErrorConverter);
	}
	
	@Override
	@SuppressWarnings("squid:S1643") // we do not use StringBuilder
	protected IAsync<SerializationException> startObjectValue(ObjectContext context, String path, List<SerializationRule> rules) {
		Object instance = context.getInstance();
		if (instance != null) {
			boolean customInstantiator = false;
			for (SerializationRule rule : rules)
				if (rule.canInstantiate(context.getOriginalType(), context)) {
					customInstantiator = true;
					break;
				}
			if (!customInstantiator && (
					!(context.getParent() instanceof AttributeContext) ||
					!((AttributeContext)context.getParent()).getAttribute().hasCustomInstantiation())
				) {
				Class<?> type = context.getOriginalType().getBase();
				if (!type.equals(instance.getClass())) {
					String attrName = "class";
					while (SerializationUtil.hasAttribute(type, attrName)) attrName = "_" + attrName;
					output.openObject();
					output.addObjectAttribute(attrName);
					return new Async<>(output.writeString(instance.getClass().getName()), ioErrorConverter);
				}
			}
		}
		return new Async<>(output.openObject(), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> endObjectValue(ObjectContext context, String path, List<SerializationRule> rules) {
		return new Async<>(output.closeObject(), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> serializeNullAttribute(AttributeContext context, String path) {
		output.addObjectAttribute(context.getAttribute().getName());
		return new Async<>(output.writeNull(), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> serializeBooleanAttribute(AttributeContext context, boolean value, String path) {
		output.addObjectAttribute(context.getAttribute().getName());
		return new Async<>(output.writeBoolean(value), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> serializeNumericAttribute(AttributeContext context, Number value, String path) {
		output.addObjectAttribute(context.getAttribute().getName());
		return new Async<>(output.writeNumber(value), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> serializeCharacterAttribute(AttributeContext context, char value, String path) {
		output.addObjectAttribute(context.getAttribute().getName());
		return new Async<>(output.writeString(new CharArrayString(value)), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> serializeStringAttribute(AttributeContext context, CharSequence value, String path) {
		output.addObjectAttribute(context.getAttribute().getName());
		return new Async<>(output.writeString(value), ioErrorConverter);
	}
	
	@Override
	protected IAsync<SerializationException> serializeObjectAttribute(
		AttributeContext context, Object value, String path, List<SerializationRule> rules
	) {
		output.addObjectAttribute(context.getAttribute().getName());
		return serializeObjectValue(context, value, context.getAttribute().getType(), path + '.' + context.getAttribute().getName(), rules);
	}
	
	@Override
	protected IAsync<SerializationException> serializeCollectionAttribute(
		CollectionContext context, String path, List<SerializationRule> rules
	) {
		Attribute colAttr = ((AttributeContext)context.getParent()).getAttribute();
		output.addObjectAttribute(colAttr.getName());
		return serializeCollectionValue(context, path + '.' + colAttr.getName(), rules);
	}

	@Override
	protected IAsync<SerializationException> serializeIOReadableValue(
		SerializationContext context, IO.Readable io, String path, List<SerializationRule> rules
	) {
		output.startString();
		IAsync<IOException> encode = io.createProducer(false).toConsumer(
			Base64Encoding.instance.new EncoderConsumer<IOException>(
				ByteArrayStringIso8859.bytesConsumer(str -> output.writeEscapedStringContent(str))
				.convert(Bytes.Readable::toByteBuffer)
			).convert(ByteArray::fromByteBuffer),
			"Serialize IO.Readable to JSON", priority);
		Async<SerializationException> result = new Async<>();
		encode.thenStart(
			Task.cpu(taskDescription, priority, new Executable.FromRunnable(() -> output.endString().onDone(result, ioErrorConverter))),
			result, ioErrorConverter);
		return result;
	}

	@Override
	protected IAsync<SerializationException> serializeIOReadableAttribute(
		AttributeContext context, IO.Readable io, String path, List<SerializationRule> rules
	) {
		output.addObjectAttribute(context.getAttribute().getName());
		return serializeIOReadableValue(context, io, path, rules);
	}
	
}
