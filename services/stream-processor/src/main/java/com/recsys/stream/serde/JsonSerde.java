package com.recsys.stream.serde;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.recsys.common.Vectors;
import com.recsys.features.FeatureJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

/**
 * JSON serde for internal state stores and repartition topics owned by this service. Contract
 * topics use Avro; internal ones only need to be readable by this topology. float[] is encoded as
 * base64 float32 to keep accumulator state compact. TODO(phase-4): binary serde for state.
 */
public final class JsonSerde<T> implements Serde<T> {
  static final ObjectMapper MAPPER =
      FeatureJson.MAPPER
          .copy()
          .registerModule(
              new SimpleModule("float-array-base64")
                  .addSerializer(float[].class, new FloatArraySerializer())
                  .addDeserializer(float[].class, new FloatArrayDeserializer()));

  private final Class<T> type;

  private JsonSerde(Class<T> type) {
    this.type = type;
  }

  public static <T> JsonSerde<T> of(Class<T> type) {
    return new JsonSerde<>(type);
  }

  @Override
  public Serializer<T> serializer() {
    return (topic, data) -> {
      if (data == null) {
        return null;
      }
      try {
        return MAPPER.writeValueAsBytes(data);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    };
  }

  @Override
  public Deserializer<T> deserializer() {
    return (topic, bytes) -> {
      if (bytes == null) {
        return null;
      }
      try {
        return MAPPER.readValue(bytes, type);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    };
  }

  static final class FloatArraySerializer extends JsonSerializer<float[]> {
    @Override
    public void serialize(float[] value, JsonGenerator gen, SerializerProvider p)
        throws IOException {
      gen.writeBinary(Vectors.toFloat32(value));
    }
  }

  static final class FloatArrayDeserializer extends JsonDeserializer<float[]> {
    @Override
    public float[] deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
      return Vectors.fromFloat32(p.getBinaryValue());
    }
  }
}
