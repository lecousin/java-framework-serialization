package net.lecousin.framework.xml.serialization;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Executable;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.encoding.Base64Encoding;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.data.Bytes;
import net.lecousin.framework.io.data.BytesFromIso8859CharArray;
import net.lecousin.framework.io.data.CharArray;
import net.lecousin.framework.math.IntegerUnit;
import net.lecousin.framework.serialization.AbstractDeserializer;
import net.lecousin.framework.serialization.SerializationClass;
import net.lecousin.framework.serialization.SerializationContext;
import net.lecousin.framework.serialization.SerializationException;
import net.lecousin.framework.serialization.SerializationUtil;
import net.lecousin.framework.serialization.TypeDefinition;
import net.lecousin.framework.serialization.SerializationClass.Attribute;
import net.lecousin.framework.serialization.SerializationContext.AttributeContext;
import net.lecousin.framework.serialization.SerializationContext.CollectionContext;
import net.lecousin.framework.serialization.SerializationContext.ObjectContext;
import net.lecousin.framework.serialization.rules.SerializationRule;
import net.lecousin.framework.text.CharArrayStringBuffer;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.xml.XMLStreamEvents;
import net.lecousin.framework.xml.XMLStreamEvents.ElementContext;
import net.lecousin.framework.xml.XMLStreamEvents.Event.Type;
import net.lecousin.framework.xml.XMLStreamEventsAsync;
import net.lecousin.framework.xml.XMLStreamReaderAsync;
import net.lecousin.framework.xml.XMLUtil;

/** XML Deserialization. */
public class XMLDeserializer extends AbstractDeserializer {

	/** Constructor. */
	public XMLDeserializer(String expectedRootNamespaceURI, String expectedRootLocalName) {
		this(expectedRootNamespaceURI, expectedRootLocalName, null);
	}
	
	/** Constructor. */
	public XMLDeserializer(String expectedRootNamespaceURI, String expectedRootLocalName, Charset encoding) {
		this.expectedRootNamespaceURI = expectedRootNamespaceURI;
		this.expectedRootLocalName = expectedRootLocalName;
		this.forceEncoding = encoding;
	}
	
	/** Create a deserializer using a given XMLStreamEventsAsync, which must be positionned on the root element for the deserialization.
	 * Because we give directly a XMLStreamEventsAsync, the method to call must be deserializeValue instead of the usual deserialize.
	 */
	public XMLDeserializer(XMLStreamEventsAsync input, String expectedRootNamespaceURI, String expectedRootLocalName) {
		this.input = input;
		this.expectedRootNamespaceURI = expectedRootNamespaceURI;
		this.expectedRootLocalName = expectedRootLocalName;
	}
	
	protected String expectedRootNamespaceURI;
	protected String expectedRootLocalName;
	protected Charset forceEncoding;
	protected XMLStreamEventsAsync input;
	
	protected static final Function<Exception, SerializationException> xmlErrorConverter =
		err -> new SerializationException("Error reading XML", err);
	
	/** Deserialize from a file accessible from the classpath. */
	public static <T> AsyncSupplier<T, SerializationException> deserializeResource(
		String resourcePath, Class<T> type, List<SerializationRule> rules, Priority priority
	) {
		IO.Readable io = LCCore.getApplication().getResource(resourcePath, priority);
		if (io == null) return new AsyncSupplier<>(null, new SerializationException("Resource not found: " + resourcePath));
		AsyncSupplier<T, SerializationException> result = deserialize(io, type, rules);
		io.closeAfter(result);
		return result;
	}
	
	/** Deserialize from a file. */
	public static <T> AsyncSupplier<T, SerializationException> deserializeFile(
		File file, Class<T> type, List<SerializationRule> rules, Priority priority
	) {
		IO.Readable io = new FileIO.ReadOnly(file, priority);
		AsyncSupplier<T, SerializationException> result = deserialize(io, type, rules);
		io.closeAfter(result);
		return result;
	}
	
	/** Deserialize from a IO.Readable. */
	@SuppressWarnings("unchecked")
	public static <T> AsyncSupplier<T, SerializationException> deserialize(IO.Readable input, Class<T> type, List<SerializationRule> rules) {
		XMLDeserializer deserializer = new XMLDeserializer(null, type.getSimpleName());
		AsyncSupplier<Object, SerializationException> res = deserializer.deserialize(
			new TypeDefinition(type), input, rules == null ? new ArrayList<>(0) : rules
		);
		AsyncSupplier<T, SerializationException> result = new AsyncSupplier<>();
		res.onDone(obj -> result.unblockSuccess((T)obj), result);
		return result;
	}
	
	protected IAsync<Exception> createAndStartReader(IO.Readable input) {
		XMLStreamReaderAsync reader = new XMLStreamReaderAsync(input, forceEncoding, 8192, 4);
		this.input = reader;
		reader.setMaximumTextSize(maxTextSize);
		reader.setMaximumCDataSize(maxTextSize);
		return reader.startRootElement();
	}
	
	@Override
	public void setMaximumTextSize(int max) {
		super.setMaximumTextSize(max);
		if (this.input != null) {
			this.input.setMaximumTextSize(maxTextSize);
			this.input.setMaximumCDataSize(maxTextSize);
		}
	}
	
	@Override
	protected IAsync<SerializationException> initializeDeserialization(IO.Readable input) {
		IAsync<Exception> start = createAndStartReader(input);
		Async<SerializationException> sp = new Async<>();
		start.onDone(() -> {
			if (this.expectedRootLocalName != null &&
				!this.input.event.localName.equals(this.expectedRootLocalName))
				sp.error(new SerializationException("Expected root XML element is " + this.expectedRootLocalName
					+ ", found is " + this.input.event.localName.asString()));
			else if (this.expectedRootNamespaceURI != null &&
					!this.input.getNamespaceURI(this.input.event.namespacePrefix).equals(this.expectedRootNamespaceURI))
				sp.error(new SerializationException("Expected root XML element namespace is " + this.expectedRootNamespaceURI
					+ ", found is " + this.input.getNamespaceURI(this.input.event.namespacePrefix)));
			else
				sp.unblock();
		}, sp, xmlErrorConverter);
		return sp;
	}
	
	@Override
	protected IAsync<SerializationException> finalizeDeserialization() {
		return new Async<>(true);
	}
	
	@Override
	protected AsyncSupplier<Boolean, SerializationException> deserializeBooleanValue(boolean nullable) {
		XMLStreamEvents.Attribute a = input.getAttributeWithNamespaceURI(XMLUtil.XSI_NAMESPACE_URI, "nil");
		if (a != null && a.value.equals("true")) {
			if (nullable)
				return new AsyncSupplier<>(null, null);
			return new AsyncSupplier<>(null, new SerializationException("null value found but boolean expected"));
		}
		AsyncSupplier<CharArrayStringBuffer, Exception> read = input.readInnerText();
		AsyncSupplier<Boolean, SerializationException> result = new AsyncSupplier<>();
		read.onDone(text -> {
			text.toLowerCase();
			if (text.equals("true") || text.equals("yes") || text.equals("1"))
				result.unblockSuccess(Boolean.TRUE);
			else if (text.equals("false") || text.equals("no") || text.equals("0"))
				result.unblockSuccess(Boolean.FALSE);
			else
				result.error(new SerializationException("Invalid boolean value: " + text.asString()));
		}, result, xmlErrorConverter);
		return result;
	}
	
	@Override
	protected AsyncSupplier<? extends Number, SerializationException> deserializeNumericValue(
		Class<?> type, boolean nullable, Class<? extends IntegerUnit> targetUnit
	) {
		XMLStreamEvents.Attribute a = input.getAttributeWithNamespaceURI(XMLUtil.XSI_NAMESPACE_URI, "nil");
		if (a != null && a.value.equals("true")) {
			if (nullable)
				return new AsyncSupplier<>(null, null);
			return new AsyncSupplier<>(null, new SerializationException("null value found but number expected"));
		}
		AsyncSupplier<CharArrayStringBuffer, Exception> read = input.readInnerText();
		AsyncSupplier<Number, SerializationException> result = new AsyncSupplier<>();
		read.onDone(text -> {
			try {
				if (targetUnit != null)
					result.unblockSuccess(convertStringToInteger(type, text.asString(), targetUnit));
				else
					convertBigDecimalValue(new BigDecimal(text.asString()), type, result);
			} catch (Exception e) {
				result.error(new SerializationException("Error deserializing numeric value", e));
			}
		}, result, xmlErrorConverter);
		return result;
	}
	
	@Override
	protected AsyncSupplier<? extends CharSequence, SerializationException> deserializeStringValue() {
		XMLStreamEvents.Attribute a = input.getAttributeWithNamespaceURI(XMLUtil.XSI_NAMESPACE_URI, "nil");
		if (a != null && a.value.equals("true"))
			return new AsyncSupplier<>(null, null);
		if (input.event.isClosed)
			return new AsyncSupplier<>(null, null);
		
		AsyncSupplier<CharArrayStringBuffer, SerializationException> result = new AsyncSupplier<>();
		CharArrayStringBuffer s = new CharArrayStringBuffer();
		Runnable onNext = new Runnable() {
			@Override
			public void run() {
				if (XMLStreamEvents.Event.Type.TEXT.equals(input.event.type)) {
					s.append(input.event.text);
					input.next().onDone(this, result, xmlErrorConverter);
					return;
				}
				if (XMLStreamEvents.Event.Type.START_ELEMENT.equals(input.event.type)) {
					result.error(new SerializationException("Unexpected XML element, text was expected"));
					return;
				}
				if (XMLStreamEvents.Event.Type.END_ELEMENT.equals(input.event.type)) {
					result.unblockSuccess(s);
					return;
				}
				input.next().onDone(this, result, xmlErrorConverter);
			}
		};
		input.next().onDone(onNext, result, xmlErrorConverter);
		return result;
	}
	
	private static class CollectionValueContext {
		private ElementContext parent;
	}
	
	private LinkedList<CollectionValueContext> colValueContext = new LinkedList<>();
	
	@Override
	protected AsyncSupplier<Boolean, SerializationException> startCollectionValue() {
		// we need to check is the collection is null
		XMLStreamEvents.Attribute a = input.getAttributeWithNamespaceURI(XMLUtil.XSI_NAMESPACE_URI, "nil");
		if (a != null && a.value.equals("true"))
			return new AsyncSupplier<>(Boolean.FALSE, null);
			
		CollectionValueContext ctx = new CollectionValueContext();
		ctx.parent = input.event.context.getFirst();
		colValueContext.addFirst(ctx);
		return new AsyncSupplier<>(Boolean.TRUE, null);
	}
	
	@Override
	protected AsyncSupplier<Pair<Object, Boolean>, SerializationException> deserializeCollectionValueElement(
		CollectionContext context, int elementIndex, String colPath, List<SerializationRule> rules
	) {
		CollectionValueContext ctx = colValueContext.getFirst();
		AsyncSupplier<Boolean, Exception> read = nextInnerElement(ctx.parent);
		if (read.isDone()) {
			if (read.hasError()) return new AsyncSupplier<>(null, xmlErrorConverter.apply(read.getError()));
			if (!read.getResult().booleanValue()) {
				colValueContext.removeFirst();
				return new AsyncSupplier<>(new Pair<>(null, Boolean.FALSE), null);
			}
			if (!input.event.localName.equals("element"))
				return new AsyncSupplier<>(null, new SerializationException("Unexpected element with name '" + input.event.localName
					+ "', name 'element' expected for a collection"));
			AsyncSupplier<Object, SerializationException> element =
				deserializeValue(context, context.getElementType(), colPath + '[' + elementIndex + ']', rules);
			if (element.isDone()) {
				if (element.hasError()) return new AsyncSupplier<>(null, element.getError());
				return new AsyncSupplier<>(new Pair<>(element.getResult(), Boolean.TRUE), null);
			}
			AsyncSupplier<Pair<Object, Boolean>, SerializationException> result = new AsyncSupplier<>();
			element.onDone(() -> result.unblockSuccess(new Pair<>(element.getResult(), Boolean.TRUE)), result);
			return result;
		}
		AsyncSupplier<Pair<Object, Boolean>, SerializationException> result = new AsyncSupplier<>();
		read.thenStart(Task.cpu(taskDescription, priority, t -> {
			if (!read.getResult().booleanValue()) {
				colValueContext.removeFirst();
				result.unblockSuccess(new Pair<>(null, Boolean.FALSE));
				return null;
			}
			AsyncSupplier<Object, SerializationException> element =
				deserializeValue(context, context.getElementType(), colPath + '[' + elementIndex + ']', rules);
			if (element.isDone()) {
				if (element.hasError()) result.error(element.getError());
				else result.unblockSuccess(new Pair<>(element.getResult(), Boolean.TRUE));
				return null;
			}
			element.onDone(() -> result.unblockSuccess(new Pair<>(element.getResult(), Boolean.TRUE)), result);
			return null;
		}), result, xmlErrorConverter);
		return result;
	}

	private static class XMLObjectContext {
		private ElementContext element;
		private int attributeIndex = 0;
		private List<XMLStreamEvents.Attribute> attributes;
		private boolean endOfAttributes = false;
		private boolean onNextAttribute = false;
		private List<String> attributesDone = new LinkedList<>();
	}
	
	private LinkedList<XMLObjectContext> objects = new LinkedList<>();
	
	@Override
	@SuppressWarnings("squid:S1643")
	protected AsyncSupplier<Object, SerializationException> startObjectValue(
		SerializationContext context, TypeDefinition type, List<SerializationRule> rules
	) {
		XMLStreamEvents.Attribute a = input.getAttributeWithNamespaceURI(XMLUtil.XSI_NAMESPACE_URI, "nil");
		if (a != null && a.value.equals("true")) {
			IAsync<Exception> close = input.closeElement();
			if (close.isDone())
				return new AsyncSupplier<>(null, close.hasError() ? xmlErrorConverter.apply(close.getError()) : null);
			AsyncSupplier<Object, SerializationException> res = new AsyncSupplier<>();
			close.onDone(() -> res.unblockSuccess(null), res, xmlErrorConverter);
			return res;
		}
		XMLObjectContext ctx = new XMLObjectContext();
		ctx.element = input.event.context.getFirst();
		ctx.attributes = input.event.attributes;
		ctx.endOfAttributes = input.event.isClosed;
		objects.addFirst(ctx);
		String attrName = "class";
		while (SerializationUtil.hasAttribute(type.getBase(), attrName)) attrName = "_" + attrName;
		a = input.removeAttributeByLocalName(attrName);
		if (a != null) {
			String className = a.value.asString();
			try {
				Class<?> cl = Class.forName(className);
				return new AsyncSupplier<>(SerializationClass.instantiate(new TypeDefinition(cl), context, rules, true), null);
			} catch (SerializationException e) {
				return new AsyncSupplier<>(null, e);
			} catch (Exception e) {
				return new AsyncSupplier<>(null, new SerializationException("Error instantiating type " + className, e));
			}
		}
		try {
			return new AsyncSupplier<>(SerializationClass.instantiate(type, context, rules, false), null);
		} catch (SerializationException e) {
			return new AsyncSupplier<>(null, e);
		} catch (Exception e) {
			return new AsyncSupplier<>(null, new SerializationException("Error instantiating type " + type, e));
		}
	}
	
	@Override
	protected AsyncSupplier<String, SerializationException> deserializeObjectAttributeName(ObjectContext context) {
		XMLObjectContext ctx = objects.getFirst();
		// first get attributes on element
		if (ctx.attributeIndex < ctx.attributes.size())
			return new AsyncSupplier<>(ctx.attributes.get(ctx.attributeIndex).localName.asString(), null);
		// then inner elements
		if (ctx.endOfAttributes) {
			try { endOfAttributes(ctx, context); }
			catch (SerializationException e) { return new AsyncSupplier<>(null, e); }
			objects.removeFirst();
			return new AsyncSupplier<>(null, null);
		}
		AsyncSupplier<Boolean, Exception> next;
		if (ctx.onNextAttribute) {
			next = new AsyncSupplier<>(Boolean.TRUE, null);
			ctx.onNextAttribute = false;
		} else {
			next = nextInnerElement(ctx.element);
		}
		AsyncSupplier<String, SerializationException> result = new AsyncSupplier<>();
		next.onDone(() -> {
			if (next.hasError()) {
				if (next.getError() instanceof EOFException) {
					objects.removeFirst();
					result.unblockSuccess(null);
					return;
				}
				result.error(xmlErrorConverter.apply(next.getError()));
				return;
			}
			if (next.getResult().booleanValue()) {
				String name = input.event.text.asString();
				ctx.attributesDone.add(name);
				result.unblockSuccess(name);
			} else {
				try { endOfAttributes(ctx, context); }
				catch (SerializationException e) {
					result.error(e);
					return;
				}
				objects.removeFirst();
				result.unblockSuccess(null);
			}
		});
		return result;
	}
	
	private static void endOfAttributes(XMLObjectContext ctx, ObjectContext context) throws SerializationException {
		// In XML, if an attribute is not present, it means it is null
		for (Attribute a : context.getSerializationClass().getAttributes()) {
			String name = a.getName();
			if (!a.canSet()) continue;
			if (a.ignore()) continue;
			if (ctx.attributesDone.contains(name)) continue;
			boolean found = false;
			for (XMLStreamEvents.Attribute xmlAttr : ctx.attributes)
				if (xmlAttr.localName.equals(name)) {
					found = true;
					break;
				}
			if (found) continue;
			// not found => set as null
			// except if it is a primitive
			if (a.getType().getBase().isPrimitive()) continue;
			// except if it is a collection
			if (Collection.class.isAssignableFrom(a.getType().getBase())) continue;
			a.setValue(context.getInstance(), null);
		}
	}
	
	@Override
	protected AsyncSupplier<Boolean, SerializationException> deserializeBooleanAttributeValue(AttributeContext context, boolean nullable) {
		XMLObjectContext ctx = objects.getFirst();
		if (ctx.attributeIndex < ctx.attributes.size()) {
			// element attribute
			XMLStreamEvents.Attribute attr = ctx.attributes.get(ctx.attributeIndex++);
			attr.value.toLowerCase();
			if (attr.value.equals("true") || attr.value.equals("yes") || attr.value.equals("1"))
				return new AsyncSupplier<>(Boolean.TRUE, null);
			else if (attr.value.equals("false") || attr.value.equals("no") || attr.value.equals("0"))
				return new AsyncSupplier<>(Boolean.FALSE, null);
			else
				return new AsyncSupplier<>(null, new SerializationException("Invalid boolean value: " + attr.value.asString()));
		}
		// inner element
		return deserializeBooleanValue(nullable);
	}
	
	@Override
	protected AsyncSupplier<? extends Number, SerializationException> deserializeNumericAttributeValue(
		AttributeContext context, boolean nullable
	) {
		XMLObjectContext ctx = objects.getFirst();
		IntegerUnit.Unit unit = context.getAttribute().getAnnotation(false, IntegerUnit.Unit.class);
		if (ctx.attributeIndex < ctx.attributes.size()) {
			// element attribute
			XMLStreamEvents.Attribute attr = ctx.attributes.get(ctx.attributeIndex++);
			if (unit != null) {
				try {
					return new AsyncSupplier<>(convertStringToInteger(context.getAttribute().getType().getBase(),
						attr.value.asString(), unit.value()), null);
				} catch (Exception e) {
					return new AsyncSupplier<>(null, new SerializationException("Error reading numeric value", e));
				}
			}
			AsyncSupplier<Number, SerializationException> result = new AsyncSupplier<>();
			try {
				BigDecimal n = new BigDecimal(attr.value.asString());
				convertBigDecimalValue(n, context.getAttribute().getType().getBase(), result);
			} catch (Exception e) {
				result.error(new SerializationException("Error reading numeric value", e));
			}
			return result;
		}
		// inner element
		return deserializeNumericValue(context.getAttribute().getType().getBase(), nullable, unit != null ? unit.value() : null);
	}
	
	@Override
	protected AsyncSupplier<? extends CharSequence, SerializationException> deserializeStringAttributeValue(AttributeContext context) {
		XMLObjectContext ctx = objects.getFirst();
		if (ctx.attributeIndex < ctx.attributes.size()) {
			// element attribute
			XMLStreamEvents.Attribute attr = ctx.attributes.get(ctx.attributeIndex++);
			return new AsyncSupplier<>(attr.value, null);
		}
		// inner element
		return deserializeStringValue();
	}
	
	@Override
	protected AsyncSupplier<Pair<Object, Boolean>, SerializationException> deserializeCollectionAttributeValueElement(
		CollectionContext context, int elementIndex, String colPath, List<SerializationRule> rules
	) {
		XMLObjectContext ctx = objects.getFirst();
		Attribute colAttr = ((AttributeContext)context.getParent()).getAttribute();
		AsyncSupplier<Pair<Object, Boolean>, SerializationException> result = new AsyncSupplier<>();
		if (elementIndex > 0) {
			// we need to go to the next element
			AsyncSupplier<Boolean, Exception> next = nextInnerElement(ctx.element);
			if (next.isDone()) {
				if (next.hasError()) {
					result.error(xmlErrorConverter.apply(next.getError()));
					return result;
				}
				if (!next.getResult().booleanValue()) {
					ctx.endOfAttributes = true;
					result.unblockSuccess(new Pair<>(null, Boolean.FALSE));
					return result;
				}
				if (!input.event.text.equals(colAttr.getName())) {
					ctx.onNextAttribute = true;
					result.unblockSuccess(new Pair<>(null, Boolean.FALSE));
					return result;
				}
			} else {
				next.thenStart(Task.cpu(taskDescription, priority, t -> {
					if (!next.getResult().booleanValue()) {
						ctx.endOfAttributes = true;
						result.unblockSuccess(new Pair<>(null, Boolean.FALSE));
						return null;
					}
					if (!input.event.text.equals(colAttr.getName())) {
						ctx.onNextAttribute = true;
						result.unblockSuccess(new Pair<>(null, Boolean.FALSE));
						return null;
					}
					readColElement(context, colPath + '[' + elementIndex + ']', rules, result);
					return null;
				}), result, xmlErrorConverter);
				return result;
			}
		}
		
		readColElement(context, colPath + '[' + elementIndex + ']', rules, result);
		return result;
	}
	
	private void readColElement(
		CollectionContext context, String elementPath, List<SerializationRule> rules, AsyncSupplier<Pair<Object, Boolean>,
		SerializationException> result
	) {
		Attribute a = null;
		SerializationContext c = context.getParent();
		while (c != null) {
			if (c instanceof AttributeContext) {
				a = ((AttributeContext)c).getAttribute();
				break;
			}
			c = c.getParent();
		}
		XMLCustomSerialization custom = a != null ? a.getAnnotation(false, XMLCustomSerialization.class) : null;
		AsyncSupplier<?, SerializationException> value = null;
		if (custom != null) {
			try {
				XMLCustomSerializer s = custom.value().newInstance();
				if (s.type().equals(context.getElementType()))
					value = s.deserialize(this, input, rules);
			} catch (Exception e) {
				result.error(new SerializationException("Error instantiating custom type", e));
				return;
			}
		}
		if (value == null)
			value = deserializeValue(context, context.getElementType(), elementPath, rules);
		if (value.isDone()) {
			if (value.hasError()) result.error(value.getError());
			else result.unblockSuccess(new Pair<>(value.getResult(), Boolean.TRUE));
		} else {
			value.onDone(obj -> result.unblockSuccess(new Pair<>(obj, Boolean.TRUE)), result);
		}
	}

	@Override
	protected AsyncSupplier<IO.Readable, SerializationException> deserializeIOReadableValue(
		SerializationContext context, List<SerializationRule> rules
	) {
		XMLStreamEvents.Attribute a = input.getAttributeWithNamespaceURI(XMLUtil.XSI_NAMESPACE_URI, "nil");
		if (a != null && a.value.equals("true"))
			return new AsyncSupplier<>(null, null);
		IAsync<Exception> next = input.next();
		AsyncSupplier<IO.Readable, SerializationException> result = new AsyncSupplier<>();
		next.thenStart(Task.cpu(taskDescription, priority, t -> {
			if (Type.TEXT.equals(input.event.type)) {
				// it may be a reference
				String ref = input.event.text.asString();
				for (StreamReferenceHandler h : streamReferenceHandlers)
					if (h.isReference(ref)) {
						h.getStreamFromReference(ref).forward(result);
						return null;
					}
			}
			// not a reference, default to base 64 encoded string
			IOInMemoryOrFile io = new IOInMemoryOrFile(128 * 1024, priority, "base 64 encoded from XML");
			AsyncConsumer<Bytes.Readable, IOException> decoder = Base64Encoding.instance.createDecoderConsumer(
				io.createConsumer(
					() -> io.seekAsync(SeekType.FROM_BEGINNING, 0)
						.onDone(() -> result.unblockSuccess(io), result, xmlErrorConverter::apply),
					err -> result.error(xmlErrorConverter.apply(err))
				).convert(Bytes.Readable::toByteBuffer),
				err -> new IOException("Base 64 encoding error in XML", err)
			);
			readBase64(decoder, io, result);
			return null;
		}), result, xmlErrorConverter);
		return result;
	}
	
	private Void readNextBase64(
		AsyncConsumer<Bytes.Readable, IOException> decoder, IOInMemoryOrFile io, AsyncSupplier<IO.Readable, SerializationException> result
	) {
		IAsync<Exception> next = input.next();
		if (next.isDone()) {
			if (next.hasError()) result.error(xmlErrorConverter.apply(next.getError()));
			else readBase64(decoder, io, result);
			return null;
		}
		next.thenStart(Task.cpu(taskDescription, priority, t -> readBase64(decoder, io, result)), result, xmlErrorConverter);
		return null;
	}
	
	private Void readBase64(
		AsyncConsumer<Bytes.Readable, IOException> decoder, IOInMemoryOrFile io, AsyncSupplier<IO.Readable, SerializationException> result
	) {
		if (Type.TEXT.equals(input.event.type)) {
			input.event.text.trim();
			if (input.event.text.isEmpty()) {
				readNextBase64(decoder, io, result);
				return null;
			}
			CharArray[] buffers = input.event.text.asCharBuffers();
			decodeBase64(decoder, io, result, buffers, 0);
			return null;
		}
		if (Type.START_ELEMENT.equals(input.event.type)) {
			input.closeElement().thenStart(Task.cpu(taskDescription, priority,
				t -> readNextBase64(decoder, io, result)), result, xmlErrorConverter);
			return null;
		}
		if (Type.END_ELEMENT.equals(input.event.type)) {
			decoder.end();
			return null;
		}
		readNextBase64(decoder, io, result);
		return null;
	}
	
	private void decodeBase64(
		AsyncConsumer<Bytes.Readable, IOException> decoder, IOInMemoryOrFile io, AsyncSupplier<IO.Readable, SerializationException> result,
		CharArray[] buffers, int index
	) {
		IAsync<IOException> decode = decoder.consume(new BytesFromIso8859CharArray(buffers[index], true));
		decode.thenStart(taskDescription, priority, (Task<Void, NoException> t) -> {
			if (decode.hasError())
				result.error(new SerializationException("Error decoding base 64", decode.getError()));
			else if (index == buffers.length - 1)
				readNextBase64(decoder, io, result);
			else
				decodeBase64(decoder, io, result, buffers, index + 1);
			return null;
		}, true);
	}

	@Override
	protected AsyncSupplier<IO.Readable, SerializationException> deserializeIOReadableAttributeValue(
		AttributeContext context, List<SerializationRule> rules
	) {
		return deserializeIOReadableValue(context, rules);
	}
	
	@Override
	protected AsyncSupplier<?, SerializationException> deserializeObjectAttributeValue(
		AttributeContext context, String path, List<SerializationRule> rules
	) {
		XMLCustomSerialization custom = context.getAttribute().getAnnotation(false, XMLCustomSerialization.class);
		if (custom != null) {
			try {
				XMLCustomSerializer s = custom.value().newInstance();
				if (s.type().equals(context.getAttribute().getType()))
					return s.deserialize(this, input, rules);
			} catch (Exception e) {
				return new AsyncSupplier<>(null, new SerializationException("Error instantiating custom type", e));
			}
		}
		return super.deserializeObjectAttributeValue(context, path, rules);
	}
	
	@SuppressWarnings("squid:S3776") // complexity
	private AsyncSupplier<Boolean, Exception> nextInnerElement(ElementContext parent) {
		if (input.event.context.isEmpty() ||
			(Type.START_ELEMENT.equals(input.event.type) && input.event.context.getFirst() == parent && input.event.isClosed) ||
			(Type.END_ELEMENT.equals(input.event.type) && input.event.context.getFirst() == parent))
			return new AsyncSupplier<>(Boolean.FALSE, null);
		IAsync<Exception> next = input.next();
		do {
			if (next.isDone()) {
				if (next.hasError()) return new AsyncSupplier<>(null, next.getError());
				if (Type.END_ELEMENT.equals(input.event.type)) {
					if (input.event.context.getFirst() == parent)
						return new AsyncSupplier<>(Boolean.FALSE, null);
				} else if (Type.START_ELEMENT.equals(input.event.type) && input.event.context.size() > 1 &&
					input.event.context.get(1) == parent) {
					return new AsyncSupplier<>(Boolean.TRUE, null);
				} else if (Type.TEXT.equals(input.event.type)) {
					if (!input.event.text.trim().isEmpty())
						return new AsyncSupplier<>(null, new SerializationException("Unexpected text: " + input.event.text));
				} else if (Type.COMMENT.equals(input.event.type)) {
					// ok, skip it
				} else {
					return new AsyncSupplier<>(null, new SerializationException("Unexpected XML " + input.event.type));
				}
				next = input.next();
				continue;
			}
			break;
		} while (true);
		AsyncSupplier<Boolean, Exception> result = new AsyncSupplier<>();
		IAsync<Exception> n = next;
		next.onDone(
			() -> {
				if (n.hasError()) result.error(n.getError());
				else if (Type.END_ELEMENT.equals(input.event.type) && input.event.context.getFirst() == parent) {
					result.unblockSuccess(Boolean.FALSE);
				} else if (Type.START_ELEMENT.equals(input.event.type) && input.event.context.size() > 1 &&
					input.event.context.get(1) == parent) {
					result.unblockSuccess(Boolean.TRUE);
				} else if (Type.TEXT.equals(input.event.type)) {
					if (!input.event.text.trim().isEmpty())
						result.error(new SerializationException("Unexpected text: " + input.event.text));
					else
						Task.cpu(taskDescription, priority,
							new Executable.FromRunnable(() -> nextInnerElement(parent).forward(result))).start();
				} else if (Type.COMMENT.equals(input.event.type)) {
					// ok, skip it
					Task.cpu(taskDescription, priority,
						new Executable.FromRunnable(() -> nextInnerElement(parent).forward(result))).start();
				} else {
					result.error(new SerializationException("Unexpected XML " + input.event.type));
				}
			}, result
		);
		return result;
	}
	
}
