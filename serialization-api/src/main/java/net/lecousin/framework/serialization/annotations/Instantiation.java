package net.lecousin.framework.serialization.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import net.lecousin.framework.serialization.SerializationClass.Attribute;
import net.lecousin.framework.serialization.rules.AbstractAttributeInstantiation;
import net.lecousin.framework.serialization.rules.SerializationRule;
import net.lecousin.framework.util.Factory;

/**
 * Allow to use another field as discriminator to know which class to instantiate when deserializing.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD,ElementType.METHOD})
public @interface Instantiation {

	/** Path of the field to use as discriminator. */
	String discriminator();
	
	/** Factory to create an instance based on the discriminator. */
	@SuppressWarnings("rawtypes")
	Class<? extends Factory> factory();
	
	/** Convert an annotation into an AttributeInstantiation rule. */
	public static class ToRule implements AttributeAnnotationToRuleOnType<Instantiation> {
		
		@Override
		public SerializationRule createRule(Instantiation annotation, Attribute attribute) {
			return new AbstractAttributeInstantiation(
				attribute.getDeclaringClass(), attribute.getOriginalName(), annotation.discriminator(), annotation.factory()
			);
		}
	}
	
}
