package net.lecousin.framework.serialization.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import net.lecousin.framework.serialization.SerializationClass.Attribute;
import net.lecousin.framework.serialization.rules.AttributeInstantiation;
import net.lecousin.framework.serialization.rules.SerializationRule;
import net.lecousin.framework.util.Factory;

/** Provides a factory to instantiate the annotated attribute during deserialization. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Instantiate {

	/** Factory to create an instance based on the container instance. */
	@SuppressWarnings("rawtypes")
	Class<? extends Factory> factory();
	
	/** Convert the annotation into a rule. */
	public static class ToRule implements AttributeAnnotationToRuleOnType<Instantiate> {
		
		@Override
		public SerializationRule createRule(Instantiate annotation, Attribute attribute) {
			return new AttributeInstantiation(
				attribute.getDeclaringClass(), attribute.getOriginalName(), annotation.factory()
			);
		}
		
	}
	
}
