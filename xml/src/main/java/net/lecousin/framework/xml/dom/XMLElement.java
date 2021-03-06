package net.lecousin.framework.xml.dom;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.text.IString;
import net.lecousin.framework.util.ObjectUtil;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.xml.XMLException;
import net.lecousin.framework.xml.XMLStreamEvents;
import net.lecousin.framework.xml.XMLStreamEvents.Event;
import net.lecousin.framework.xml.XMLStreamEventsAsync;
import net.lecousin.framework.xml.XMLStreamEventsSync;

import org.w3c.dom.Attr;
import org.w3c.dom.DOMException;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.TypeInfo;

/** DOM Element. */
public class XMLElement extends XMLNode implements Element {

	// TODO use a XMLDocument as owner of all nodes...
	
	/** Constructor. */
	public XMLElement(XMLDocument doc, String prefix, String localName) {
		super(doc);
		this.prefix = prefix;
		this.localName = localName;
	}
	
	/** Constructor. */
	public XMLElement(XMLDocument doc, XMLStreamEvents.ElementContext context) {
		this(doc, context.namespacePrefix.asString(), context.localName.asString());
		for (Pair<IString, IString> ns : context.namespaces)
			declareNamespace(ns.getValue2().asString(), ns.getValue1().asString());
		if (context.defaultNamespace != null && !context.defaultNamespace.isEmpty())
			declareNamespace(context.defaultNamespace.asString(), "");
	}
	
	protected String prefix;
	protected String localName;
	protected Map<String, String> prefixToURI = null;
	protected LinkedList<XMLAttribute> attributes = new LinkedList<>();
	protected LinkedList<XMLNode> children = new LinkedList<>();

	@Override
	public XMLElement cloneNode(boolean deep) {
		XMLElement clone = new XMLElement(doc, prefix, localName);
		if (prefixToURI != null) clone.prefixToURI = new HashMap<>(prefixToURI);
		for (XMLAttribute a : attributes)
			clone.addAttribute(a.cloneNode(false));
		if (deep)
			for (XMLNode child : children)
				clone.appendChild(child.cloneNode(true));
		cloned(clone);
		return clone;
	}
	
	@Override
	public short getNodeType() {
		return Node.ELEMENT_NODE;
	}
	
	@Override
	public String getNodeName() {
		if (prefix != null && prefix.length() > 0)
			return prefix + ':' + localName;
		return localName;
	}
	
	@Override
	public String getTagName() {
		return getNodeName();
	}
	
	@Override
	public String getLocalName() {
		return localName;
	}
	
	@Override
	public String getPrefix() {
		return prefix == null || prefix.isEmpty() ? null : prefix;
	}
	
	@Override
	public void setPrefix(String prefix) {
		this.prefix = prefix;
	}
	
	@Override
	public String getNamespaceURI() {
		return lookupNamespaceURI(prefix);
		//return prefixToURI == null ? null : prefixToURI.get(prefix);
	}
	
	@Override
	public XMLNode appendChild(Node newChild) {
		if (!(newChild instanceof XMLNode)) throw DOMErrors.invalidChildType(newChild);
		if (newChild == this) throw DOMErrors.cannotBeAChildOfItself();
		XMLNode child = (XMLNode)newChild;
		if (isAncestor(child)) throw DOMErrors.cannotAddAnAncestor();
		child.setParent(this);
		children.add(child);
		return child;
	}
	
	@Override
	public XMLNode insertBefore(Node newChild, Node refChild) {
		if (!(newChild instanceof XMLNode)) throw DOMErrors.invalidChildType(newChild);
		if (newChild == this) throw DOMErrors.cannotBeAChildOfItself();
		XMLNode child = (XMLNode)newChild;
		if (isAncestor(child)) throw DOMErrors.cannotAddAnAncestor();
		if (refChild == null) {
			child.setParent(this);
			children.add(child);
			return child;
		}
		int i = children.indexOf(refChild);
		if (i < 0)
			throw new DOMException(DOMException.HIERARCHY_REQUEST_ERR, "refChild is not a child of this element");
		child.setParent(this);
		children.add(i, child);
		return child;
	}
	
	@Override
	public XMLNode removeChild(Node oldChild) {
		if (!(oldChild instanceof XMLNode))
			throw new DOMException(DOMException.HIERARCHY_REQUEST_ERR, "oldChild must implement XMLNode");
		XMLNode child = (XMLNode)oldChild;
		if (child.parent != this)
			throw new DOMException(DOMException.HIERARCHY_REQUEST_ERR, "The given child is not a child of this Element");
		child.parent = null;
		children.remove(child);
		return child;
	}
	
	@Override
	public XMLNode replaceChild(Node newChild, Node oldChild) {
		if (!(newChild instanceof XMLNode)) throw DOMErrors.invalidChildType(newChild);
		if (newChild == this) throw DOMErrors.cannotBeAChildOfItself();
		if (!(oldChild instanceof XMLNode)) throw DOMErrors.invalidChildType(oldChild);
		XMLNode neChild = (XMLNode)newChild;
		XMLNode olChild = (XMLNode)oldChild;
		if (olChild.parent != this)
			throw new DOMException(DOMException.HIERARCHY_REQUEST_ERR, "The given oldChild is not a child of this Element");
		if (isAncestor(neChild)) throw DOMErrors.cannotAddAnAncestor();
		int i = children.indexOf(olChild);
		olChild.parent = null;
		neChild.setParent(this);
		children.set(i, neChild);
		return olChild;
	}
	
	@Override
	public Node getFirstChild() {
		return children.peekFirst();
	}
	
	@Override
	public Node getLastChild() {
		return children.peekLast();
	}
	
	@Override
	public boolean hasChildNodes() {
		return !children.isEmpty();
	}

	@Override
	public NodeList getChildNodes() {
		return new XMLNodeList(children);
	}
	
	
	@Override
	public boolean hasAttributes() {
		return !attributes.isEmpty();
	}
	
	@Override
	public boolean hasAttribute(String name) {
		for (XMLAttribute a : attributes)
			if (a.getNodeName().equals(name))
				return true;
		return false;
	}
	
	@Override
	public NamedNodeMap getAttributes() {
		return new XMLNamedNodeMap(attributes);
	}
	
	@Override
	public String getAttribute(String name) {
		for (XMLAttribute a : attributes)
			if (a.getNodeName().equals(name))
				return a.getNodeValue();
		return "";
	}

	@Override
	public void setAttribute(String name, String value) {
		for (XMLAttribute a : attributes)
			if (a.getNodeName().equals(name)) {
				a.setNodeValue(value);
				return;
			}
		addAttribute(new XMLAttribute(doc, "", name, value));
	}

	@Override
	public void removeAttribute(String name) {
		for (Iterator<XMLAttribute> it = attributes.iterator(); it.hasNext(); ) {
			XMLAttribute a = it.next();
			if (a.getNodeName().equals(name)) {
				a.parent = null;
				it.remove();
				return;
			}
		}
	}

	@Override
	public XMLAttribute getAttributeNode(String name) {
		for (XMLAttribute a : attributes)
			if (a.getNodeName().equals(name))
				return a;
		return null;
	}

	@Override
	public XMLAttribute setAttributeNode(Attr newAttr) {
		if (!(newAttr instanceof XMLAttribute))
			throw new DOMException(DOMException.WRONG_DOCUMENT_ERR, "newAttr must be a XMLAttribute");
		XMLAttribute na = (XMLAttribute)newAttr;
		for (ListIterator<XMLAttribute> it = attributes.listIterator(); it.hasNext(); ) {
			XMLAttribute a = it.next();
			if (a.isEqualNode(na)) {
				it.set(na);
				a.parent = null;
				na.setParent(this);
				return na;
			}
		}
		addAttribute(na);
		return na;
	}

	@Override
	public XMLAttribute removeAttributeNode(Attr oldAttr) {
		if (!(oldAttr instanceof XMLAttribute))
			throw new DOMException(DOMException.WRONG_DOCUMENT_ERR, "oldAttr must be a XMLAttribute");
		XMLAttribute na = (XMLAttribute)oldAttr;
		for (Iterator<XMLAttribute> it = attributes.iterator(); it.hasNext(); ) {
			XMLAttribute a = it.next();
			if (a.isEqualNode(na)) {
				it.remove();
				a.parent = null;
				return a;
			}
		}
		throw new DOMException(DOMException.NOT_FOUND_ERR, "Attribute not found in this element");
	}
	
	@Override
	public boolean hasAttributeNS(String namespaceURI, String localName) {
		String prefx = getPrefixForNamespaceURI(namespaceURI);
		if (prefx == null)
			return false;
		for (XMLAttribute a : attributes)
			if (prefx.equals(a.prefix) && localName.equals(a.localName))
				return true;
		return false;
	}
	
	@Override
	public String getAttributeNS(String namespaceURI, String localName) {
		String prefx = getPrefixForNamespaceURI(namespaceURI);
		if (prefx == null)
			return "";
		for (XMLAttribute a : attributes)
			if (prefx.equals(a.prefix) && localName.equals(a.localName))
				return a.value == null ? "" : a.value;
		return "";
	}

	@Override
	public void setAttributeNS(String namespaceURI, String qualifiedName, String value) {
		int i = qualifiedName.indexOf(':');
		String pref;
		String local;
		if (i < 0) {
			pref = "";
			local = qualifiedName;
		} else {
			pref = qualifiedName.substring(0, i);
			local = qualifiedName.substring(i + 1);
		}
		if (prefixToURI != null) {
			String uri = prefixToURI.get(pref);
			if (uri != null && !uri.equals(namespaceURI))
				throw new DOMException(DOMException.NAMESPACE_ERR, "Prefix " + pref + " is already used for namespace " + uri);
			if (uri == null)
				prefixToURI.put(pref, namespaceURI);
		} else if (namespaceURI != null || !pref.isEmpty()) {
			declareNamespace(namespaceURI, pref);
		}
		XMLAttribute a = new XMLAttribute(doc, pref, local, value);
		setAttributeNode(a);
	}

	@Override
	public void removeAttributeNS(String namespaceURI, String localName) {
		for (Iterator<XMLAttribute> it = attributes.iterator(); it.hasNext(); ) {
			XMLAttribute a = it.next();
			if (a.localName.equals(localName) && namespaceURI.equals(a.getNamespaceURI())) {
				it.remove();
				a.parent = null;
				return;
			}
		}
	}

	@Override
	public XMLAttribute getAttributeNodeNS(String namespaceURI, String localName) {
		for (Iterator<XMLAttribute> it = attributes.iterator(); it.hasNext(); ) {
			XMLAttribute a = it.next();
			if (a.localName.equals(localName) && namespaceURI.equals(a.getNamespaceURI()))
				return a;
		}
		return null;
	}

	@Override
	public XMLAttribute setAttributeNodeNS(Attr newAttr) {
		if (!(newAttr instanceof XMLAttribute))
			throw new DOMException(DOMException.WRONG_DOCUMENT_ERR, "newAttr must be a XMLAttribute");
		XMLAttribute na = (XMLAttribute)newAttr;
		for (ListIterator<XMLAttribute> it = attributes.listIterator(); it.hasNext(); ) {
			XMLAttribute a = it.next();
			if (a.getName().equals(na.getName())) {
				it.set(na);
				a.parent = null;
				na.setParent(this);
				return na;
			}
		}
		addAttribute(na);
		return na;
	}
	
	@Override
	public void setIdAttribute(String name, boolean isId) {
		XMLAttribute a = getAttributeNode(name);
		if (a == null) throw DOMErrors.attributeDoesNotExist(name);
		a.isId = isId;
	}

	@Override
	public void setIdAttributeNS(String namespaceURI, String localName, boolean isId) {
		XMLAttribute a = getAttributeNodeNS(namespaceURI, localName);
		if (a == null) throw DOMErrors.attributeDoesNotExist(localName);
		a.isId = isId;
	}

	@Override
	public void setIdAttributeNode(Attr idAttr, boolean isId) {
		if (!attributes.contains(idAttr)) throw DOMErrors.attributeDoesNotExist(localName);
		XMLAttribute a = (XMLAttribute)idAttr;
		a.isId = isId;
	}
	
	/** Add an attribute. */
	public void addAttribute(XMLAttribute a) {
		a.setParent(this);
		attributes.add(a);
	}

	/** Search the prefix for the given namespace URI. */
	public String getPrefixForNamespaceURI(String namespaceURI) {
		if (namespaceURI == null)
			return "";
		if (prefixToURI != null) {
			for (Map.Entry<String, String> e : prefixToURI.entrySet())
				if (e.getValue().equals(namespaceURI))
					return e.getKey();
		}
		if (parent == null)
			return null;
		if (parent instanceof XMLElement)
			return ((XMLElement)parent).getPrefixForNamespaceURI(namespaceURI);
		return parent.lookupPrefix(namespaceURI);
	}
	
	@Override
	public String lookupPrefix(String namespaceURI) {
		if (prefixToURI != null)
			for (Map.Entry<String, String> e : prefixToURI.entrySet())
				if (e.getValue().equals(namespaceURI) &&
					e.getKey().length() > 0)
						return e.getKey();
		if (parent == null)
			return null;
		return parent.lookupPrefix(namespaceURI);
	}

	@Override
	public boolean isDefaultNamespace(String namespaceURI) {
		if (prefixToURI != null)
			for (Map.Entry<String, String> e : prefixToURI.entrySet())
				if (e.getKey().isEmpty())
					return e.getValue().equals(namespaceURI);
		if (parent == null)
			return false;
		return parent.isDefaultNamespace(namespaceURI);
	}

	@Override
	public String lookupNamespaceURI(String prefix) {
		if (prefixToURI != null) {
			String uri = prefixToURI.get(prefix);
			if (uri != null) return uri;
		}
		if (parent == null)
			return null;
		return parent.lookupNamespaceURI(prefix);
	}

	/** Declare a namespace on this element (xmlns). */
	public void declareNamespace(String uri, String prefix) {
		if (prefixToURI == null)
			prefixToURI = new HashMap<>(5);
		prefixToURI.put(prefix, uri);
	}
	
	@Override
	public NodeList getElementsByTagName(String name) {
		LinkedList<XMLNode> elements = new LinkedList<>();
		if ("*".equals(name)) name = null;
		getElementsByTagName(name, elements);
		return new XMLNodeList(elements);
	}
	
	protected void getElementsByTagName(String name, List<XMLNode> result) {
		for (XMLNode child : children) {
			if (!(child instanceof XMLElement)) continue;
			XMLElement e = (XMLElement)child;
			if (name == null || e.getNodeName().equals(name))
				result.add(e);
			e.getElementsByTagName(name, result);
		}
	}
	
	/** Search for elements. */
	protected void getElementsByTagName(String namespaceURI, String localName, List<XMLNode> result) {
		for (XMLNode child : children) {
			if (!(child instanceof XMLElement)) continue;
			XMLElement e = (XMLElement)child;
			if (("*".equals(localName) || e.getLocalName().equals(localName)) &&
				("*".equals(namespaceURI) || ObjectUtil.equalsOrNull(namespaceURI, e.getNamespaceURI())))
				result.add(e);
			e.getElementsByTagName(namespaceURI, localName, result);
		}
	}

	@Override
	public NodeList getElementsByTagNameNS(String namespaceURI, String localName) {
		LinkedList<XMLNode> elements = new LinkedList<>();
		getElementsByTagName(namespaceURI, localName, elements);
		return new XMLNodeList(elements);
	}

	@Override
	public TypeInfo getSchemaTypeInfo() {
		return null;
	}
	
	@Override
	public String getTextContent() {
		if (children.isEmpty()) return "";
		StringBuilder s = new StringBuilder();
		for (XMLNode child : children) {
			if (child instanceof XMLComment) continue;
			if (child instanceof XMLText &&
				((XMLText)child).isElementContentWhitespace()) continue;
			s.append(child.getTextContent());
		}
		return s.toString();
	}
	
	@Override
	public void setTextContent(String textContent) {
		while (!children.isEmpty())
			removeChild(children.getFirst());
		appendChild(new XMLText(doc, textContent));
	}

	@Override
	@SuppressWarnings("squid:ForLoopCounterChangedCheck")
	public void normalize() {
		for (int pos = 1; pos < children.size(); ++pos) {
			XMLNode child = children.get(pos);
			if (!(child instanceof XMLText)) continue;
			XMLNode prev = children.get(pos - 1);
			if (!(prev instanceof XMLText)) continue;
			prev.setTextContent(prev.getTextContent() + child.getTextContent());
			removeChild(child);
			pos--;
		}
	}

	
	/** Create an Element from a XMLStreamEvents. */
	public static XMLElement create(XMLDocument doc, XMLStreamEventsSync stream) throws XMLException, IOException {
		if (!Event.Type.START_ELEMENT.equals(stream.event.type))
			throw new IllegalStateException("Method XMLElement.create must be called with a stream being on a START_ELEMENT event");
		XMLElement element = new XMLElement(doc, stream.event.context.getFirst());
		for (XMLStreamEvents.Attribute a : stream.event.attributes)
			element.addAttribute(new XMLAttribute(doc, a.namespacePrefix.asString(),
				a.localName.asString(), a.value != null ? a.value.asString() : null));
		element.parseContent(stream);
		return element;
	}
	
	/** Create an Element from a XMLStreamEvents. */
	public static AsyncSupplier<XMLElement, Exception> create(XMLDocument doc, XMLStreamEventsAsync stream) {
		if (!Event.Type.START_ELEMENT.equals(stream.event.type))
			throw new IllegalStateException("Method XMLElement.create must be called with a stream being on a START_ELEMENT event");
		XMLElement element = new XMLElement(doc, stream.event.context.getFirst());
		for (XMLStreamEvents.Attribute a : stream.event.attributes)
			element.addAttribute(new XMLAttribute(doc, a.namespacePrefix.asString(),
				a.localName.asString(), a.value != null ? a.value.asString() : null));
		IAsync<Exception> parse = element.parseContent(stream);
		AsyncSupplier<XMLElement, Exception> result = new AsyncSupplier<>();
		if (parse.isDone()) {
			if (parse.hasError()) result.error(parse.getError());
			else result.unblockSuccess(element);
			return result;
		}
		parse.onDone(() -> result.unblockSuccess(element), result);
		return result;
	}
	
	/** Parse the content of this element. */
	public void parseContent(XMLStreamEventsSync stream) throws XMLException, IOException {
		if (stream.event.isClosed) return;
		do {
			stream.next();
			switch (stream.event.type) {
			case START_ELEMENT:
				appendChild(create(doc, stream));
				break;
			case END_ELEMENT:
				return;
			case TEXT:
				appendChild(new XMLText(doc, stream.event.text.asString()));
				break;
			case CDATA:
				appendChild(new XMLCData(doc, stream.event.text.asString()));
				break;
			case COMMENT:
				appendChild(new XMLComment(doc, stream.event.text.asString()));
				break;
			case PROCESSING_INSTRUCTION:
				// TODO
				break;
			default:
				// TODO XMLException
				throw new IOException("Unexpected XML event " + stream.event.type + " in an element");
			}
		} while (true);
	}
	
	/** Parse the content of this element. */
	public IAsync<Exception> parseContent(XMLStreamEventsAsync stream) {
		if (stream.event.isClosed) return new Async<>(true);
		return parseContent(stream, null);
	}
	
	@SuppressWarnings("squid:S1199") // nested block
	private IAsync<Exception> parseContent(XMLStreamEventsAsync stream, IAsync<Exception> s) {
		do {
			IAsync<Exception> next = s != null ? s : stream.next();
			if (next.isDone()) {
				if (next.hasError()) return next;
				switch (stream.event.type) {
				case START_ELEMENT: {
					AsyncSupplier<XMLElement, Exception> child = create(doc, stream);
					if (child.isDone()) {
						if (child.hasError()) return child;
						appendChild(child.getResult());
						break;
					}
					Async<Exception> sp = new Async<>();
					child.thenStart("Parsing XML to DOM", stream.getPriority(), (Task<Void, NoException> t) -> {
						appendChild(child.getResult());
						parseContent(stream, null).onDone(sp);
						return null;
					}, sp);
					return sp;
				}
				case END_ELEMENT:
					return next;
				case TEXT:
					appendChild(new XMLText(doc, stream.event.text.asString()));
					break;
				case CDATA:
					appendChild(new XMLCData(doc, stream.event.text.asString()));
					break;
				case COMMENT:
					appendChild(new XMLComment(doc, stream.event.text.asString()));
					break;
				case PROCESSING_INSTRUCTION:
					// TODO
					break;
				default:
					// TODO XMLException
					return new Async<>(
						new IOException("Unexpected XML event " + stream.event.type + " in an element"));
				}
				s = null;
				continue;
			}
			// blocked
			Async<Exception> sp = new Async<>();
			next.thenStart("Parsing XML to DOM", stream.getPriority(), (Task<Void, NoException> t) -> {
				parseContent(stream, next).onDone(sp);
				return null;
			}, sp);
			return sp;
		} while (true);
	}

}
