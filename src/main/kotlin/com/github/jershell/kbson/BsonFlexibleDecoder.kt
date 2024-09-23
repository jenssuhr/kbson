package com.github.jershell.kbson

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeDecoder.Companion.UNKNOWN_NAME
import kotlinx.serialization.modules.SerializersModule
import org.bson.AbstractBsonReader
import org.bson.AbstractBsonReader.State
import org.bson.BsonType
import org.bson.types.ObjectId

abstract class FlexibleDecoder(
    val reader: AbstractBsonReader,
    override val serializersModule: SerializersModule,
    val configuration: Configuration
) : AbstractDecoder() {
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        return when (descriptor.kind) {
            StructureKind.CLASS -> {
                val current = reader.currentBsonType
                if (current == null || current == BsonType.DOCUMENT) {
                    reader.readStartDocument()
                }
                BsonFlexibleDecoder(reader, serializersModule, configuration)
            }
            StructureKind.MAP -> {
                reader.readStartDocument()
                MapDecoder(reader, serializersModule, configuration)
            }
            StructureKind.LIST -> {
                reader.readStartArray()
                ListDecoder(reader, serializersModule, configuration)
            }
            is PolymorphicKind -> {
                reader.readStartDocument()
                PolymorphismDecoder(reader, serializersModule, configuration)
            }
            else -> this
        }
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        when (descriptor.kind) {
            is StructureKind.LIST -> reader.readEndArray()
            is StructureKind.MAP, StructureKind.CLASS, StructureKind.OBJECT -> reader.readEndDocument()
            else -> {}
/*            SerialKind.ENUM -> TODO()
            SerialKind.CONTEXTUAL -> TODO()
            PrimitiveKind.BOOLEAN -> TODO()
            PrimitiveKind.BYTE -> TODO()
            PrimitiveKind.CHAR -> TODO()
            PrimitiveKind.SHORT -> TODO()
            PrimitiveKind.INT -> TODO()
            PrimitiveKind.LONG -> TODO()
            PrimitiveKind.FLOAT -> TODO()
            PrimitiveKind.DOUBLE -> TODO()
            PrimitiveKind.STRING -> TODO()
            PolymorphicKind.SEALED -> TODO()
            PolymorphicKind.OPEN -> TODO()*/
        }
    }

    override fun decodeNotNullMark(): Boolean {
        return reader.currentBsonType != BsonType.NULL
    }

    override fun decodeBoolean(): Boolean {
        return reader.readBoolean()
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val name = reader.readString()
        val value = enumDescriptor.getElementIndex(name)
        return if (value == UNKNOWN_NAME) {
            throw SerializationException("Enum has unknown value $name")
        } else {
            value
        }
    }

    override fun decodeByte(): Byte {
        return reader.readInt32().toByte()
    }

    override fun decodeNull(): Nothing? {
        reader.readNull()
        return null
    }

    override fun decodeChar(): Char {
        return reader.readSymbol().first()
    }

    override fun decodeDouble(): Double {
        return reader.readDouble()
    }

    override fun decodeInt(): Int {
        return reader.readInt32()
    }

    override fun decodeShort(): Short {
        return reader.readInt32().toShort()
    }

    override fun decodeLong(): Long {
        return reader.readInt64()
    }

    override fun decodeFloat(): Float {
        return reader.readDouble().toFloat()
    }

    override fun decodeString(): String {
        return reader.readString()
    }
}

class BsonFlexibleDecoder(
    reader: AbstractBsonReader,
    context: SerializersModule,
    configuration: Configuration,
) : FlexibleDecoder(reader, context, configuration) {

    //to handle not optional nullable properties
    private var indexesSet: BooleanArray? = null
    private var containsNotOptionalNullable: Boolean? = null
    private var checkNotOptionalNullable = false

    private fun initNotOptionalProperties(desc: SerialDescriptor) {
        if (containsNotOptionalNullable == null) {
            for (i in 0 until desc.elementsCount) {
                if (!desc.isElementOptional(i)) {
                    val nullable =
                        try {
                            desc.getElementDescriptor(i).isNullable
                        } catch (e: Exception) {
                            true
                        }
                    if (nullable) {
                        containsNotOptionalNullable = true
                        break
                    }
                }
            }
            if (containsNotOptionalNullable == null) {
                containsNotOptionalNullable = false
            } else {
                indexesSet = BooleanArray(desc.elementsCount)
            }
        }
    }

    private fun checkNotOptionalProperties(desc: SerialDescriptor): Int {
        //set to null not optional nullable properties if any
        if (containsNotOptionalNullable!!) {
            for (i in 0 until desc.elementsCount) {
                if (indexesSet?.get(i) != true
                    && !desc.isElementOptional(i)
                    && try {
                        desc.getElementDescriptor(i).isNullable
                    } catch (e: Exception) {
                        true
                    }
                ) {
                    checkNotOptionalNullable = true
                    indexesSet!![i] = true
                    return i
                }
            }
        }
        return CompositeDecoder.DECODE_DONE
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        initNotOptionalProperties(descriptor)

        if (reader.state == State.TYPE) {
            reader.readBsonType()
        }
        return when (reader.state) {
            State.NAME -> {
                val currentName = reader.readName()
                val index = descriptor.getElementIndex(currentName)
                if (index == UNKNOWN_NAME) {
                    reader.skipValue()
                    decodeElementIndex(descriptor)
                } else {
                    if (containsNotOptionalNullable!!) {
                        indexesSet!![index] = true
                    }
                    index
                }
            }
            else -> {
                checkNotOptionalProperties(descriptor)
            }
        }
    }

    override fun decodeNotNullMark(): Boolean {
        return !checkNotOptionalNullable && reader.currentBsonType != BsonType.NULL
    }

    override fun decodeNull(): Nothing? {
        if (!checkNotOptionalNullable) {
            reader.readNull()
        }
        return null
    }
}

private class PolymorphismDecoder(
    reader: AbstractBsonReader,
    val context: SerializersModule,
    configuration: Configuration,
) : FlexibleDecoder(reader, context, configuration) {
    private var decodeCount = 0
    private var discriminatorValue: String? = null

    @InternalSerializationApi
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T =
        deserializer.deserialize(BsonFlexibleDecoder(reader, context, configuration))

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val classDiscriminator = descriptor.classDiscriminator()
        return when (decodeCount) {
            0 -> {
                if (reader.state == State.TYPE) {
                    reader.readBsonType()
                }

                val mark = reader.mark

                while (discriminatorValue == null) {
                    when(reader.state) {
                        State.TYPE -> {
                            reader.readBsonType()
                        }
                        State.NAME -> {
                            val fieldName = reader.readName()
                            if (fieldName == classDiscriminator) {
                                discriminatorValue = reader.readString()
                                break
                            } else {
                                reader.skipValue()
                            }
                        }
                        else -> {
                            return CompositeDecoder.DECODE_DONE
                        }
                    }
                }
                if (discriminatorValue == null) {
                    error("Class discriminator field '$classDiscriminator' not found")
                }

                mark.reset()
                decodeCount++
            }
            1 -> {
                decodeCount++
            }
            else -> CompositeDecoder.DECODE_DONE
        }
    }

    override fun decodeString(): String  {
        val currentDiscriminatorValue = discriminatorValue
        return if (currentDiscriminatorValue != null) {
            discriminatorValue = null
            currentDiscriminatorValue
        } else {
            super.decodeString()
        }
    }

    private fun SerialDescriptor.classDiscriminator(): String {
        for (annotation in this.annotations) {
            if (annotation is BsonClassDiscriminator) {
                return annotation.discriminator
            }
        }
        return configuration.classDiscriminator
    }
}

private class MapDecoder(
    reader: AbstractBsonReader,
    context: SerializersModule,
    configuration: Configuration
) : FlexibleDecoder(reader, context, configuration) {

    private var index = 0
    private var key: Boolean = false

    override fun decodeBoolean(): Boolean {
        return if (key) {
            reader.readName()!!.toBoolean()
        } else {
            super.decodeBoolean()
        }
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        return if (key) {
            enumDescriptor.getElementIndex(reader.readName()!!)
        } else {
            super.decodeEnum(enumDescriptor)
        }
    }

    override fun decodeByte(): Byte {
        return if (key) {
            reader.readName()!!.toByte()
        } else {
            super.decodeByte()
        }
    }

    override fun decodeChar(): Char {
        return if (key) {
            reader.readName()!!.first()
        } else {
            super.decodeChar()
        }
    }

    override fun decodeDouble(): Double {
        return if (key) {
            reader.readName()!!.toDouble()
        } else {
            super.decodeDouble()
        }
    }

    override fun decodeInt(): Int {
        return if (key) {
            reader.readName()!!.toInt()
        } else {
            super.decodeInt()
        }
    }

    override fun decodeShort(): Short {
        return if (key) {
            reader.readName()!!.toShort()
        } else {
            super.decodeShort()
        }
    }

    override fun decodeLong(): Long {
        return if (key) {
            reader.readName()!!.toLong()
        } else {
            super.decodeLong()
        }
    }

    override fun decodeFloat(): Float {
        return if (key) {
            reader.readName()!!.toFloat()
        } else {
            super.decodeFloat()
        }
    }

    override fun decodeString(): String {
        return if (key) {
            reader.readName()
        } else {
            super.decodeString()
        }
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (!key) {
            key = true
            val nextType = reader.readBsonType()
            if (nextType == BsonType.END_OF_DOCUMENT) return CompositeDecoder.DECODE_DONE
        } else {
            key = false
        }
        return index++
    }
}

private class ListDecoder(
    reader: AbstractBsonReader,
    context: SerializersModule,
    configuration: Configuration
) : FlexibleDecoder(reader, context, configuration) {
    private var index = 0
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val nextType = reader.readBsonType()
        return if (nextType == BsonType.END_OF_DOCUMENT) CompositeDecoder.DECODE_DONE else index++
    }
}
