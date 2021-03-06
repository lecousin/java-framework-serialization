package net.lecousin.framework.serialization.annotations;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.serialization.SerializationClass;
import net.lecousin.framework.serialization.SerializationClass.Attribute;
import net.lecousin.framework.serialization.rules.SerializationRule;
import net.lecousin.framework.util.ClassUtil;
import net.lecousin.framework.util.Pair;

/** Convert an annotation into a SerializationRule.
 * @param <TAnnotation> type of annotation
 */
public interface TypeAnnotationToRule<TAnnotation extends Annotation> {

	/** Create a rule from an annotation. */
	SerializationRule createRule(TAnnotation annotation, Class<?> type);

	/** Search for annotations on the given type, and try to convert them into
	 * serialization rules.
	 */
	static List<SerializationRule> addRules(SerializationClass type, List<SerializationRule> rules) {
		rules = addRules(type.getType().getBase(), rules);
		for (Attribute a : type.getAttributes())
			rules = addRules(a.getType().getBase(), rules);
		return rules;
	}
	
	/** Search for annotations on the given type, and try to convert them into
	 * serialization rules.
	 */
	static List<SerializationRule> addRules(Class<?> clazz, List<SerializationRule> rules) {
		List<SerializationRule> newRules = new LinkedList<>();
		processAnnotations(clazz, newRules, rules);
		if (newRules.isEmpty())
			return rules;
		ArrayList<SerializationRule> newList = new ArrayList<>(rules.size() + newRules.size());
		newList.addAll(rules);
		newList.addAll(newRules);
		return newList;
	}
	
	/** Convert annotations into rules. */
	@SuppressWarnings({ "rawtypes" })
	static void processAnnotations(Class<?> clazz, List<SerializationRule> newRules, List<SerializationRule> rules) {
		for (Annotation a : ClassUtil.expandRepeatableAnnotations(clazz.getDeclaredAnnotations())) {
			for (TypeAnnotationToRule toRule : getAnnotationToRules(a)) {
				addRule(toRule, a, clazz, newRules, rules);
			}
		}
		if (clazz.getSuperclass() != null)
			processAnnotations(clazz.getSuperclass(), newRules, rules);
		for (Class<?> i : clazz.getInterfaces())
			processAnnotations(i, newRules, rules);
	}
	
	/** Internal method to add rule. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	static void addRule(
		TypeAnnotationToRule toRule, Annotation a, Class<?> clazz,
		List<SerializationRule> newRules, List<SerializationRule> rules
	) {
		SerializationRule rule;
		try { rule = toRule.createRule(a, clazz); }
		catch (Exception t) {
			LCCore.getApplication().getDefaultLogger().error(
				"Error creating rule from annotation " + a.annotationType().getName()
				+ " using " + toRule.getClass().getName(), t);
			return;
		}
		SerializationRule.addRuleIfNoEquivalent(rule, newRules, rules);
	}
	
	/** Search for implementations to convert the given annotation into a rule.
	 * It looks first on the annotation class if there is an inner class implementing AttributeAnnotationToRuleOnAttribute.
	 * If none is found, it looks into the registry.
	 */
	static List<TypeAnnotationToRule<?>> getAnnotationToRules(Annotation a) {
		LinkedList<TypeAnnotationToRule<?>> list = new LinkedList<>();
		for (Class<?> c : a.annotationType().getDeclaredClasses()) {
			if (!TypeAnnotationToRule.class.isAssignableFrom(c)) continue;
			try { list.add((TypeAnnotationToRule<?>)c.newInstance()); }
			catch (Exception t) {
				LCCore.getApplication().getDefaultLogger().error(
					"Error creating TypeAnnotationToRule " + a.annotationType().getName(), t);
			}
		}
		for (Pair<Class<? extends Annotation>, TypeAnnotationToRule<?>> p : Registry.registry)
			if (p.getValue1().equals(a.annotationType()))
				list.add(p.getValue2());
		return list;
	}
	
	/** Registry of converters between annotations and serialization rules. */
	public static final class Registry {
		
		private Registry() {
			// no instance
		}

		private static List<Pair<Class<? extends Annotation>, TypeAnnotationToRule<?>>> registry = new ArrayList<>();

		/** Register a converter. */
		public static <T extends Annotation> void register(Class<T> annotationType, TypeAnnotationToRule<T> toRule) {
			registry.add(new Pair<>(annotationType, toRule));
		}
	}
	
}
