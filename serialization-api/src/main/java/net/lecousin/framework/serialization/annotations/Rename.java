package net.lecousin.framework.serialization.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import net.lecousin.framework.serialization.SerializationClass.Attribute;
import net.lecousin.framework.serialization.rules.RenameAttribute;
import net.lecousin.framework.serialization.rules.SerializationRule;

/** Specify a different name for serialization on a given class and attribute. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD,ElementType.METHOD,ElementType.TYPE})
@Repeatable(Renames.class)
public @interface Rename {

	/** The class. */
	Class<?> value();

	/** The attribute name. */
	String attribute();

	/** The new name. */
	String newName();
	
	/** Convert an annotation into a rule. */
	public static class ToRule implements AttributeAnnotationToRuleOnType<Rename> {
		
		@Override
		public SerializationRule createRule(Rename annotation, Attribute attribute) {
			return new RenameAttribute(annotation.value(), annotation.attribute(), annotation.newName());
		}
		
	}
	
}
