package net.lecousin.framework.io.serialization;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.lecousin.framework.util.ClassUtil;

/** Utilities for serialization. */
public final class SerializationUtil {
	
	private SerializationUtil() {
		// no instance
	}

	/** Used to serialize a Map entry.
	 * @param <Key> type of key
	 * @param <Value> type of value
	 */
	@SuppressWarnings("squid:ClassVariableVisibilityCheck")
	public static class MapEntry<Key, Value> {
		public Key key;
		public Value value;
	}
	
	/** Return true if the given class contains the requested attribute, either as a field or with a getter or setter. */
	public static boolean hasAttribute(Class<?> type, String name) {
		if (type.equals(Object.class)) return false;
		for (Field f : type.getDeclaredFields())
			if (f.getName().equals(name))
				return true;
		Method m;
		m = ClassUtil.getGetter(type, name);
		if (m != null && !m.getDeclaringClass().equals(Object.class)) return true;
		m = ClassUtil.getSetter(type, name);
		if (m != null && !m.getDeclaringClass().equals(Object.class)) return true;
		if (type.getSuperclass() != null)
			return hasAttribute(type.getSuperclass(), name);
		return false;
	}

}
