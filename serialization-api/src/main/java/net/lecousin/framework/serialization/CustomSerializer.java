package net.lecousin.framework.serialization;

/** Customize the serialization. */
public interface CustomSerializer {

	/** Source type. */
	TypeDefinition sourceType();

	/** Target type. */
	TypeDefinition targetType();
	
	/** Serialize an object of SourceType into a TargetType. */
	Object serialize(Object src, Object containerInstance) throws SerializationException;
	
	/** Deserialize an object of TargetType into a SourceType. */
	Object deserialize(Object src, Object containerInstance) throws SerializationException;
	
}
