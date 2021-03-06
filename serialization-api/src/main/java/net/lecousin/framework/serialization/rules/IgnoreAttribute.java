package net.lecousin.framework.serialization.rules;

import java.util.List;

import net.lecousin.framework.serialization.SerializationClass;
import net.lecousin.framework.serialization.SerializationContext;
import net.lecousin.framework.serialization.SerializationContextPattern;
import net.lecousin.framework.serialization.SerializationClass.Attribute;

/**
 * This rule ignore a specific attribute or all attributes in a class.
 */
public class IgnoreAttribute implements SerializationRule {

	/** Constructor. */
	public IgnoreAttribute(SerializationContextPattern contextPattern) {
		this.contextPattern = contextPattern;
	}

	/** Constructor to ignore a specific attribute. */
	public IgnoreAttribute(Class<?> type, String name) {
		this(new SerializationContextPattern.OnClassAttribute(type, name));
	}

	/** Constructor to ignore all attributes in a class. */
	public IgnoreAttribute(Class<?> type) {
		this(new SerializationContextPattern.OnClass(type));
	}
	
	private SerializationContextPattern contextPattern;
	
	@Override
	@SuppressWarnings("squid:S3516") // always return false
	public boolean apply(SerializationClass type, SerializationContext context, List<SerializationRule> rules, boolean serializing) {
		if (!contextPattern.matches(type, context))
			return false;
		for (Attribute a : type.getAttributes())
			if (contextPattern.matches(type, context, a))
				a.ignore(true);
		return false;
	}
	
	@Override
	public boolean isEquivalent(SerializationRule rule) {
		if (!(rule instanceof IgnoreAttribute)) return false;
		IgnoreAttribute r = (IgnoreAttribute)rule;
		return contextPattern.isEquivalent(r.contextPattern);
	}

}
