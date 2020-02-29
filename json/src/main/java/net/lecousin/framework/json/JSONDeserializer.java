package net.lecousin.framework.json;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.encoding.Base64Encoding;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.io.data.Bytes;
import net.lecousin.framework.io.data.BytesFromIso8859String;
import net.lecousin.framework.io.serialization.AbstractDeserializer;
import net.lecousin.framework.io.serialization.SerializationClass;
import net.lecousin.framework.io.serialization.SerializationContext;
import net.lecousin.framework.io.serialization.SerializationContext.AttributeContext;
import net.lecousin.framework.io.serialization.SerializationContext.CollectionContext;
import net.lecousin.framework.io.serialization.SerializationContext.ObjectContext;
import net.lecousin.framework.io.serialization.SerializationException;
import net.lecousin.framework.io.serialization.SerializationUtil;
import net.lecousin.framework.io.serialization.TypeDefinition;
import net.lecousin.framework.io.serialization.rules.SerializationRule;
import net.lecousin.framework.json.JSONReaderAsync.EventType;
import net.lecousin.framework.math.IntegerUnit;
import net.lecousin.framework.util.Pair;

/**
 * JSON Deserializer.
 */
public class JSONDeserializer extends AbstractDeserializer {
	
	/** Constructor. */
	public JSONDeserializer() {
		this(StandardCharsets.UTF_8);
	}
	
	/** Constructor. */
	public JSONDeserializer(Charset encoding) {
		this.encoding = encoding;
	}
	
	protected Charset encoding;
	protected JSONReaderAsync input;
	
	static SerializationException convertError(Exception e) {
		return JSONDeserializationException.errorReadingJSON(e);
	}
	
	@Override
	protected IAsync<SerializationException> initializeDeserialization(IO.Readable input) {
		this.input = new JSONReaderAsync(input, encoding);
		this.input.setMaximumTextSize(maxTextSize);
		return new Async<>(true);
	}
	
	@Override
	protected IAsync<SerializationException> finalizeDeserialization() {
		return new Async<>(true);
	}
	
	@Override
	public void setMaximumTextSize(int max) {
		super.setMaximumTextSize(max);
		if (this.input != null)
			this.input.setMaximumTextSize(maxTextSize);
	}
	
	protected IAsync<Exception> eventBack = null;
	
	protected IAsync<Exception> nextEvent() {
		if (eventBack != null) {
			IAsync<Exception> ev = eventBack;
			eventBack = null;
			return ev;
		}
		return input.next();
	}
	
	protected void back() {
		eventBack = new Async<>(true);
	}
	
	@Override
	protected AsyncSupplier<Boolean, SerializationException> deserializeBooleanValue(boolean nullable) {
		IAsync<Exception> next = nextEvent();
		AsyncSupplier<Boolean, SerializationException> result = new AsyncSupplier<>();
		next.onDone(() -> {
			if (EventType.NULL.equals(input.event)) {
				if (nullable) result.unblockSuccess(null);
				else result.error(JSONDeserializationException.unexpectedNull("boolean"));
			} else if (!EventType.BOOLEAN.equals(input.event)) {
				result.error(JSONDeserializationException.unexpectedValue("boolean"));
			} else {
				result.unblockSuccess(input.bool);
			}
		}, result, JSONDeserializer::convertError);
		return result;
	}
	
	@Override
	protected AsyncSupplier<? extends Number, SerializationException> deserializeNumericValue(
		Class<?> type, boolean nullable, Class<? extends IntegerUnit> targetUnit
	) {
		IAsync<Exception> next = nextEvent();
		AsyncSupplier<Number, SerializationException> result = new AsyncSupplier<>();
		next.onDone(() -> {
			if (EventType.NULL.equals(input.event)) {
				if (nullable) result.unblockSuccess(null);
				else result.error(JSONDeserializationException.unexpectedNull("number"));
			} else if (EventType.NUMBER.equals(input.event)) {
				convertBigDecimalValue(
					input.number instanceof BigDecimal
					? (BigDecimal)input.number
					: new BigDecimal((BigInteger)input.number),
					type, result);
			} else if (EventType.STRING.equals(input.event)) {
				if (targetUnit != null)
					try { result.unblockSuccess(convertStringToInteger(type, input.string.asString(), targetUnit)); }
					catch (Exception e) { result.error(new JSONDeserializationException("Error reading numeric value", e)); }
				else
					try { convertBigDecimalValue(new BigDecimal(input.string.asString()), type, result); }
					catch (Exception e) { result.error(new JSONDeserializationException("Error reading numeric value", e)); }
			} else {
				result.error(JSONDeserializationException.unexpectedValue("number"));
			}
		}, result, JSONDeserializer::convertError);
		return result;
	}
	
	@Override
	protected AsyncSupplier<? extends CharSequence, SerializationException> deserializeStringValue() {
		IAsync<Exception> next = nextEvent();
		AsyncSupplier<CharSequence, SerializationException> result = new AsyncSupplier<>();
		next.onDone(() -> {
			if (EventType.NULL.equals(input.event))
				result.unblockSuccess(null);
			else if (!EventType.STRING.equals(input.event))
				result.error(JSONDeserializationException.unexpectedValue("string"));
			else
				result.unblockSuccess(input.string);
		}, result, JSONDeserializer::convertError);
		return result;
	}

	@Override
	protected AsyncSupplier<Boolean, SerializationException> startCollectionValue() {
		IAsync<Exception> next = nextEvent();
		AsyncSupplier<Boolean, SerializationException> result = new AsyncSupplier<>();
		next.onDone(() -> {
			if (EventType.NULL.equals(input.event))
				result.unblockSuccess(Boolean.FALSE);
			else if (!EventType.START_ARRAY.equals(input.event))
				result.error(JSONDeserializationException.unexpectedValue("array"));
			else
				result.unblockSuccess(Boolean.TRUE);
		}, result, JSONDeserializer::convertError);
		return result;
	}
	
	@Override
	protected AsyncSupplier<Pair<Object, Boolean>, SerializationException> deserializeCollectionValueElement(
		CollectionContext context, int elementIndex, String colPath, List<SerializationRule> rules
	) {
		IAsync<Exception> next = nextEvent();
		AsyncSupplier<Pair<Object, Boolean>, SerializationException> result = new AsyncSupplier<>();
		next.thenDoOrStart("Deserializing JSON", priority, () -> {
			if (EventType.END_ARRAY.equals(input.event)) result.unblockSuccess(new Pair<>(null, Boolean.FALSE));
			else if (EventType.NULL.equals(input.event)) result.unblockSuccess(new Pair<>(null, Boolean.TRUE));
			else {
				back();
				AsyncSupplier<Object, SerializationException> value = deserializeValue(
					context, context.getElementType(), colPath + '[' + elementIndex + ']', rules);
				if (value.isDone()) {
					if (value.hasError()) result.error(value.getError());
					else result.unblockSuccess(new Pair<>(value.getResult(), Boolean.TRUE));
				} else {
					value.onDone(obj -> result.unblockSuccess(new Pair<>(obj, Boolean.TRUE)), result);
				}
			}
		}, result, JSONDeserializer::convertError);
		return result;
	}
	
	@Override
	protected AsyncSupplier<Object, SerializationException> startObjectValue(
		SerializationContext context, TypeDefinition type, List<SerializationRule> rules
	) {
		IAsync<Exception> next = nextEvent();
		if (next.isDone()) {
			if (next.hasError()) return new AsyncSupplier<>(null, JSONDeserializationException.errorReadingJSON(next.getError()));
			if (EventType.NULL.equals(input.event)) return new AsyncSupplier<>(null, null);
			if (!EventType.START_OBJECT.equals(input.event))
				return new AsyncSupplier<>(null, JSONDeserializationException.unexpectedValue("object"));
			AsyncSupplier<Object, SerializationException> result = new AsyncSupplier<>();
			instantiate(context, type, rules, result);
			return result;
		}
		AsyncSupplier<Object, SerializationException> result = new AsyncSupplier<>();
		next.onDone(() -> {
			if (EventType.NULL.equals(input.event))
				result.unblockSuccess(null);
			else if (!EventType.START_OBJECT.equals(input.event))
				result.error(JSONDeserializationException.unexpectedValue("object"));
			else
				Task.cpu(taskDescription, priority, () -> instantiate(context, type, rules, result)).start();
		}, result, JSONDeserializer::convertError);
		return result;
	}

	private Void instantiate(
		SerializationContext context, TypeDefinition type, List<SerializationRule> rules, AsyncSupplier<Object, SerializationException> result
	) {
		nextEvent()
		.thenDoOrStart(taskDescription, priority, () -> instantiate2(context, type, rules, result), result, JSONDeserializer::convertError);
		return null;
	}
	
	@SuppressWarnings("squid:S1643") // we do not use StringBuilder
	private Void instantiate2(
		SerializationContext context, TypeDefinition type, List<SerializationRule> rules, AsyncSupplier<Object, SerializationException> result
	) {
		if (EventType.END_OBJECT.equals(input.event)) {
			back();
			try { result.unblockSuccess(SerializationClass.instantiate(type, context, rules, false)); }
			catch (Exception e) { result.error(JSONDeserializationException.instantiationError(e)); }
			return null;
		}
		if (EventType.START_ATTRIBUTE.equals(input.event)) {
			String attrName = "class";
			while (SerializationUtil.hasAttribute(type.getBase(), attrName)) attrName = "_" + attrName;
			if (!input.string.equals(attrName)) {
				back();
				try { result.unblockSuccess(SerializationClass.instantiate(type, context, rules, false)); }
				catch (Exception e) { result.error(JSONDeserializationException.instantiationError(e)); }
				return null;
			}
			IAsync<Exception> next = nextEvent();
			next.thenDoOrStart("JSON Deserialization", priority, () -> {
				if (!EventType.STRING.equals(input.event))
					result.error(JSONDeserializationException.unexpectedValue("a string containing a class name"));
				else
					instantiate3(context, rules, result);
			}, result, JSONDeserializer::convertError);
			return null;
		}
		result.error(JSONDeserializationException.unexpected("event " + input.event, "attribute or end of object"));
		return null;
	}
	
	private void instantiate3(SerializationContext context, List<SerializationRule> rules, AsyncSupplier<Object, SerializationException> result) {
		String className = input.string.asString();
		try {
			Class<?> cl = Class.forName(className);
			result.unblockSuccess(SerializationClass.instantiate(new TypeDefinition(cl), context, rules, true));
		} catch (Exception e) { result.error(JSONDeserializationException.instantiationError(e)); }
	}
	
	@Override
	protected AsyncSupplier<String, SerializationException> deserializeObjectAttributeName(ObjectContext context) {
		IAsync<Exception> next = nextEvent();
		AsyncSupplier<String, SerializationException> result = new AsyncSupplier<>();
		next.onDone(() -> {
			if (EventType.START_ATTRIBUTE.equals(input.event))
				result.unblockSuccess(input.string.asString());
			else if (EventType.END_OBJECT.equals(input.event))
				result.unblockSuccess(null);
			else
				result.error(JSONDeserializationException.unexpected("event " + input.event, "attribute or end of object"));
		}, result, JSONDeserializer::convertError);
		return result;
	}
	
	private static final String BASE64_OR_STREAM_EXPECTED = "array with base 64 encoded strings, or stream reference";

	@Override
	protected AsyncSupplier<IO.Readable, SerializationException> deserializeIOReadableValue(
		SerializationContext context, List<SerializationRule> rules
	) {
		IAsync<Exception> next = nextEvent();
		AsyncSupplier<IO.Readable, SerializationException> result = new AsyncSupplier<>();
		next.onDone(() -> {
			if (EventType.NULL.equals(input.event))
				result.unblockSuccess(null);
			if (EventType.STRING.equals(input.event)) {
				// it may be a reference
				String ref = input.string.asString();
				for (StreamReferenceHandler h : streamReferenceHandlers) {
					if (h.isReference(ref)) {
						h.getStreamFromReference(ref).forward(result);
						return;
					}
				}
				try {
					byte[] decoded = Base64Encoding.instance.decode(new BytesFromIso8859String(input.string));
					result.unblockSuccess(new ByteArrayIO(decoded, "base 64 from JSON"));
				} catch (Exception e) {
					result.error(new JSONDeserializationException("Invalid base 64", e));
				}
			} else if (EventType.LONG_STRING.equals(input.event)) {
				FileIO.ReadWrite file;
				try { file = input.getLongStringFile(); }
				catch (IOException e) {
					result.error(convertError(e));
					return;
				}
				IOInMemoryOrFile io = new IOInMemoryOrFile(128 * 1024, priority, "base 64 encoded from JSON");
				AsyncConsumer<ByteBuffer, IOException> ioConsumer = io.createConsumer(
					() -> io.seekAsync(SeekType.FROM_BEGINNING, 0)
						.onDone(() -> result.unblockSuccess(io), result, JSONDeserializer::convertError),
					err -> result.error(convertError(err))
				);
				result.onError(err -> io.closeAsync());
				AsyncConsumer<Bytes.Readable, IOException> consumer = Base64Encoding.instance.createDecoderConsumer(
					ioConsumer.convert(Bytes.Readable::toByteBuffer), IO::error
				);
				file.createProducer(true).toConsumer(consumer.convert(ByteArray::fromByteBuffer),
					"Decode base 64 stream from JSON", Task.Priority.NORMAL);
			} else {
				result.error(JSONDeserializationException.unexpectedValue(BASE64_OR_STREAM_EXPECTED));
			}
		}, result, JSONDeserializer::convertError);
		return result;
	}

	@Override
	protected AsyncSupplier<IO.Readable, SerializationException> deserializeIOReadableAttributeValue(
		AttributeContext context, List<SerializationRule> rules
	) {
		return deserializeIOReadableValue(context, rules);
	}

}
