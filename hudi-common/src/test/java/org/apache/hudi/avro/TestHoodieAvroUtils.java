/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.avro;

import org.apache.hudi.avro.model.BooleanWrapper;
import org.apache.hudi.avro.model.BytesWrapper;
import org.apache.hudi.avro.model.DateWrapper;
import org.apache.hudi.avro.model.DecimalWrapper;
import org.apache.hudi.avro.model.DoubleWrapper;
import org.apache.hudi.avro.model.FloatWrapper;
import org.apache.hudi.avro.model.IntWrapper;
import org.apache.hudi.avro.model.LongWrapper;
import org.apache.hudi.avro.model.StringWrapper;
import org.apache.hudi.avro.model.TimestampMicrosWrapper;
import org.apache.hudi.common.model.HoodieAvroRecord;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.model.RewriteAvroPayload;
import org.apache.hudi.common.testutils.HoodieTestDataGenerator;
import org.apache.hudi.common.testutils.SchemaTestUtil;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.exception.SchemaCompatibilityException;

import org.apache.avro.AvroRuntimeException;
import org.apache.avro.Conversions;
import org.apache.avro.JsonProperties;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.util.Utf8;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.hudi.avro.HoodieAvroUtils.getNestedFieldSchemaFromWriteSchema;
import static org.apache.hudi.avro.HoodieAvroUtils.sanitizeName;
import static org.apache.hudi.avro.HoodieAvroUtils.unwrapAvroValueWrapper;
import static org.apache.hudi.avro.HoodieAvroUtils.wrapValueIntoAvro;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests hoodie avro utilities.
 */
public class TestHoodieAvroUtils {

  private static final String EVOLVED_SCHEMA = "{\"type\": \"record\",\"name\": \"testrec1\",\"fields\": [ "
      + "{\"name\": \"timestamp\",\"type\": \"double\"},{\"name\": \"_row_key\", \"type\": \"string\"},"
      + "{\"name\": \"non_pii_col\", \"type\": \"string\"},"
      + "{\"name\": \"pii_col\", \"type\": \"string\", \"column_category\": \"user_profile\"},"
      + "{\"name\": \"new_col_not_nullable_default_dummy_val\", \"type\": \"string\", \"default\": \"dummy_val\"},"
      + "{\"name\": \"new_col_nullable_wo_default\", \"type\": [\"int\", \"null\"]},"
      + "{\"name\": \"new_col_nullable_default_null\", \"type\": [\"null\" ,\"string\"],\"default\": null},"
      + "{\"name\": \"new_col_nullable_default_dummy_val\", \"type\": [\"string\" ,\"null\"],\"default\": \"dummy_val\"}]}";

  private static final String EXAMPLE_SCHEMA = "{\"type\": \"record\",\"name\": \"testrec\",\"fields\": [ "
      + "{\"name\": \"timestamp\",\"type\": \"double\"},{\"name\": \"_row_key\", \"type\": \"string\"},"
      + "{\"name\": \"non_pii_col\", \"type\": \"string\"},"
      + "{\"name\": \"pii_col\", \"type\": \"string\", \"column_category\": \"user_profile\"}]}";

  private static final String EXAMPLE_SCHEMA_WITH_PROPS = "{\"type\": \"record\",\"name\": \"testrec\",\"fields\": [ "
      + "{\"name\": \"timestamp\",\"type\": \"double\", \"custom_field_property\":\"value\"},{\"name\": \"_row_key\", \"type\": \"string\"},"
      + "{\"name\": \"non_pii_col\", \"type\": \"string\"},"
      + "{\"name\": \"pii_col\", \"type\": \"string\", \"column_category\": \"user_profile\"}], "
      + "\"custom_schema_property\": \"custom_schema_property_value\"}";

  private static final int NUM_FIELDS_IN_EXAMPLE_SCHEMA = 4;

  private static final String SCHEMA_WITH_METADATA_FIELD = "{\"type\": \"record\",\"name\": \"testrec2\",\"fields\": [ "
      + "{\"name\": \"timestamp\",\"type\": \"double\"},{\"name\": \"_row_key\", \"type\": \"string\"},"
      + "{\"name\": \"non_pii_col\", \"type\": \"string\"},"
      + "{\"name\": \"pii_col\", \"type\": \"string\", \"column_category\": \"user_profile\"},"
      + "{\"name\": \"_hoodie_commit_time\", \"type\": [\"null\", \"string\"]},"
      + "{\"name\": \"nullable_field\",\"type\": [\"null\" ,\"string\"],\"default\": null},"
      + "{\"name\": \"nullable_field_wo_default\",\"type\": [\"null\" ,\"string\"]}]}";

  private static final String SCHEMA_WITH_NON_NULLABLE_FIELD = "{\"type\": \"record\",\"name\": \"testrec3\",\"fields\": [ "
      + "{\"name\": \"timestamp\",\"type\": \"double\"},{\"name\": \"_row_key\", \"type\": \"string\"},"
      + "{\"name\": \"non_pii_col\", \"type\": \"string\"},"
      + "{\"name\": \"pii_col\", \"type\": \"string\", \"column_category\": \"user_profile\"},"
      + "{\"name\": \"nullable_field\",\"type\": [\"null\" ,\"string\"],\"default\": null},"
      + "{\"name\": \"non_nullable_field_wo_default\",\"type\": \"string\"},"
      + "{\"name\": \"non_nullable_field_with_default\",\"type\": \"string\", \"default\": \"dummy\"}]}";

  private static final String SCHEMA_WITH_NON_NULLABLE_FIELD_WITH_DEFAULT = "{\"type\": \"record\",\"name\": \"testrec4\",\"fields\": [ "
      + "{\"name\": \"timestamp\",\"type\": \"double\"},{\"name\": \"_row_key\", \"type\": \"string\"},"
      + "{\"name\": \"non_pii_col\", \"type\": \"string\"},"
      + "{\"name\": \"pii_col\", \"type\": \"string\", \"column_category\": \"user_profile\"},"
      + "{\"name\": \"nullable_field\",\"type\": [\"null\" ,\"string\"],\"default\": null},"
      + "{\"name\": \"non_nullable_field_with_default\",\"type\": \"string\", \"default\": \"dummy\"}]}";

  private static final String SCHEMA_WITH_DECIMAL_FIELD = "{\"type\":\"record\",\"name\":\"record\",\"fields\":["
      + "{\"name\":\"key_col\",\"type\":[\"null\",\"int\"],\"default\":null},"
      + "{\"name\":\"decimal_col\",\"type\":[\"null\","
      + "{\"type\":\"bytes\",\"logicalType\":\"decimal\",\"precision\":8,\"scale\":4}],\"default\":null}]}";

  private static final String SCHEMA_WITH_NESTED_FIELD = "{\"name\":\"MyClass\",\"type\":\"record\",\"namespace\":\"com.acme.avro\",\"fields\":["
      + "{\"name\":\"firstname\",\"type\":\"string\"},"
      + "{\"name\":\"lastname\",\"type\":\"string\"},"
      + "{\"name\":\"student\",\"type\":{\"name\":\"student\",\"type\":\"record\",\"fields\":["
      + "{\"name\":\"firstname\",\"type\":[\"null\" ,\"string\"],\"default\": null},{\"name\":\"lastname\",\"type\":[\"null\" ,\"string\"],\"default\": null}]}}]}";

  private static final String SCHEMA_WITH_NESTED_FIELD_RENAMED = "{\"name\":\"MyClass\",\"type\":\"record\",\"namespace\":\"com.acme.avro\",\"fields\":["
      + "{\"name\":\"fn\",\"type\":\"string\"},"
      + "{\"name\":\"ln\",\"type\":\"string\"},"
      + "{\"name\":\"ss\",\"type\":{\"name\":\"ss\",\"type\":\"record\",\"fields\":["
      + "{\"name\":\"fn\",\"type\":[\"null\" ,\"string\"],\"default\": null},{\"name\":\"ln\",\"type\":[\"null\" ,\"string\"],\"default\": null}]}}]}";

  private static final String SCHEMA_WITH_AVRO_TYPES = "{\"name\":\"TestRecordAvroTypes\",\"type\":\"record\",\"fields\":["
      // Primitive types
      + "{\"name\":\"booleanField\",\"type\":\"boolean\"},"
      + "{\"name\":\"intField\",\"type\":\"int\"},"
      + "{\"name\":\"longField\",\"type\":\"long\"},"
      + "{\"name\":\"floatField\",\"type\":\"float\"},"
      + "{\"name\":\"doubleField\",\"type\":\"double\"},"
      + "{\"name\":\"bytesField\",\"type\":\"bytes\"},"
      + "{\"name\":\"stringField\",\"type\":\"string\"},"
      // Logical types
      + "{\"name\":\"decimalField\",\"type\":\"bytes\",\"logicalType\":\"decimal\",\"precision\":20,\"scale\":5},"
      + "{\"name\":\"timeMillisField\",\"type\":\"int\",\"logicalType\":\"time-millis\"},"
      + "{\"name\":\"timeMicrosField\",\"type\":\"long\",\"logicalType\":\"time-micros\"},"
      + "{\"name\":\"timestampMillisField\",\"type\":\"long\",\"logicalType\":\"timestamp-millis\"},"
      + "{\"name\":\"timestampMicrosField\",\"type\":\"long\",\"logicalType\":\"timestamp-micros\"},"
      + "{\"name\":\"localTimestampMillisField\",\"type\":\"long\",\"logicalType\":\"local-timestamp-millis\"},"
      + "{\"name\":\"localTimestampMicrosField\",\"type\":\"long\",\"logicalType\":\"local-timestamp-micros\"}"
      + "]}";

  @Test
  public void testPropsPresent() {
    Schema schema = HoodieAvroUtils.addMetadataFields(new Schema.Parser().parse(EXAMPLE_SCHEMA));
    boolean piiPresent = false;
    for (Schema.Field field : schema.getFields()) {
      if (HoodieAvroUtils.isMetadataField(field.name())) {
        continue;
      }

      assertNotNull(field.name(), "field name is null");
      Map<String, Object> props = field.getObjectProps();
      assertNotNull(props, "The property is null");

      if (field.name().equals("pii_col")) {
        piiPresent = true;
        assertTrue(props.containsKey("column_category"), "sensitivity_level is removed in field 'pii_col'");
      } else {
        assertEquals(0, props.size(), "The property shows up but not set");
      }
    }
    assertTrue(piiPresent, "column pii_col doesn't show up");
  }

  @Test
  public void testDefaultValue() {
    GenericRecord rec = new GenericData.Record(new Schema.Parser().parse(EVOLVED_SCHEMA));
    rec.put("_row_key", "key1");
    rec.put("non_pii_col", "val1");
    rec.put("pii_col", "val2");
    rec.put("timestamp", 3.5);
    Schema schemaWithMetadata = HoodieAvroUtils.addMetadataFields(new Schema.Parser().parse(EVOLVED_SCHEMA));
    GenericRecord rec1 = HoodieAvroUtils.rewriteRecord(rec, schemaWithMetadata);
    assertEquals("dummy_val", rec1.get("new_col_not_nullable_default_dummy_val"));
    assertNull(rec1.get("new_col_nullable_wo_default"));
    assertNull(rec1.get("new_col_nullable_default_null"));
    assertEquals("dummy_val", rec1.get("new_col_nullable_default_dummy_val"));
    assertNull(rec1.get(HoodieRecord.RECORD_KEY_METADATA_FIELD));
  }

  @Test
  public void testDefaultValueWithSchemaEvolution() {
    GenericRecord rec = new GenericData.Record(new Schema.Parser().parse(EXAMPLE_SCHEMA));
    rec.put("_row_key", "key1");
    rec.put("non_pii_col", "val1");
    rec.put("pii_col", "val2");
    rec.put("timestamp", 3.5);
    GenericRecord rec1 = HoodieAvroUtils.rewriteRecord(rec, new Schema.Parser().parse(EVOLVED_SCHEMA));
    assertEquals("dummy_val", rec1.get("new_col_not_nullable_default_dummy_val"));
    assertNull(rec1.get("new_col_nullable_wo_default"));
  }

  @Test
  public void testMetadataField() {
    GenericRecord rec = new GenericData.Record(new Schema.Parser().parse(EXAMPLE_SCHEMA));
    rec.put("_row_key", "key1");
    rec.put("non_pii_col", "val1");
    rec.put("pii_col", "val2");
    rec.put("timestamp", 3.5);
    GenericRecord rec1 = HoodieAvroUtils.rewriteRecord(rec, new Schema.Parser().parse(SCHEMA_WITH_METADATA_FIELD));
    assertNull(rec1.get("_hoodie_commit_time"));
    assertNull(rec1.get("nullable_field"));
    assertNull(rec1.get("nullable_field_wo_default"));
  }

  @Test
  public void testNonNullableFieldWithoutDefault() {
    GenericRecord rec = new GenericData.Record(new Schema.Parser().parse(EXAMPLE_SCHEMA));
    rec.put("_row_key", "key1");
    rec.put("non_pii_col", "val1");
    rec.put("pii_col", "val2");
    rec.put("timestamp", 3.5);
    assertThrows(SchemaCompatibilityException.class, () -> HoodieAvroUtils.rewriteRecord(rec, new Schema.Parser().parse(SCHEMA_WITH_NON_NULLABLE_FIELD)));
  }

  @Test
  public void testNonNullableFieldWithDefault() {
    GenericRecord rec = new GenericData.Record(new Schema.Parser().parse(EXAMPLE_SCHEMA));
    rec.put("_row_key", "key1");
    rec.put("non_pii_col", "val1");
    rec.put("pii_col", "val2");
    rec.put("timestamp", 3.5);
    GenericRecord rec1 = HoodieAvroUtils.rewriteRecord(rec, new Schema.Parser().parse(SCHEMA_WITH_NON_NULLABLE_FIELD_WITH_DEFAULT));
    assertEquals("dummy", rec1.get("non_nullable_field_with_default"));
  }

  @Test
  public void testJsonNodeNullWithDefaultValues() {
    List<Schema.Field> fields = new ArrayList<>();
    Schema initialSchema = Schema.createRecord("test_record", "test record", "org.test.namespace", false);
    Schema.Field field1 = new Schema.Field("key", HoodieAvroUtils.METADATA_FIELD_SCHEMA, "", JsonProperties.NULL_VALUE);
    Schema.Field field2 = new Schema.Field("key1", HoodieAvroUtils.METADATA_FIELD_SCHEMA, "", JsonProperties.NULL_VALUE);
    Schema.Field field3 = new Schema.Field("key2", HoodieAvroUtils.METADATA_FIELD_SCHEMA, "", JsonProperties.NULL_VALUE);
    fields.add(field1);
    fields.add(field2);
    fields.add(field3);
    initialSchema.setFields(fields);
    GenericRecord rec = new GenericData.Record(initialSchema);
    rec.put("key", "val");
    rec.put("key1", "val1");
    rec.put("key2", "val2");

    List<Schema.Field> evolvedFields = new ArrayList<>();
    Schema evolvedSchema = Schema.createRecord("evolved_record", "evolved record", "org.evolved.namespace", false);
    Schema.Field evolvedField1 = new Schema.Field("key", HoodieAvroUtils.METADATA_FIELD_SCHEMA, "", JsonProperties.NULL_VALUE);
    Schema.Field evolvedField2 = new Schema.Field("key1", HoodieAvroUtils.METADATA_FIELD_SCHEMA, "", JsonProperties.NULL_VALUE);
    Schema.Field evolvedField3 = new Schema.Field("key2", HoodieAvroUtils.METADATA_FIELD_SCHEMA, "", JsonProperties.NULL_VALUE);
    Schema.Field evolvedField4 = new Schema.Field("evolved_field", HoodieAvroUtils.METADATA_FIELD_SCHEMA, "", JsonProperties.NULL_VALUE);
    Schema.Field evolvedField5 = new Schema.Field("evolved_field1", HoodieAvroUtils.METADATA_FIELD_SCHEMA, "", JsonProperties.NULL_VALUE);
    evolvedFields.add(evolvedField1);
    evolvedFields.add(evolvedField2);
    evolvedFields.add(evolvedField3);
    evolvedFields.add(evolvedField4);
    evolvedFields.add(evolvedField5);
    evolvedSchema.setFields(evolvedFields);

    GenericRecord rec1 = HoodieAvroUtils.rewriteRecord(rec, evolvedSchema);
    //evolvedField4.defaultVal() returns a JsonProperties.Null instance.
    assertNull(rec1.get("evolved_field"));
    //evolvedField5.defaultVal() returns null.
    assertNull(rec1.get("evolved_field1"));
  }

  @Test
  public void testAddingAndRemovingMetadataFields() {
    Schema schemaWithMetaCols = HoodieAvroUtils.addMetadataFields(new Schema.Parser().parse(EXAMPLE_SCHEMA));
    assertEquals(NUM_FIELDS_IN_EXAMPLE_SCHEMA + HoodieRecord.HOODIE_META_COLUMNS.size(), schemaWithMetaCols.getFields().size());
    Schema schemaWithoutMetaCols = HoodieAvroUtils.removeMetadataFields(schemaWithMetaCols);
    assertEquals(NUM_FIELDS_IN_EXAMPLE_SCHEMA, schemaWithoutMetaCols.getFields().size());
  }

  @Test
  public void testRemoveFields() {
    // partitioned table test.
    String schemaStr = "{\"type\": \"record\",\"name\": \"testrec\",\"fields\": [ "
        + "{\"name\": \"timestamp\",\"type\": \"double\"},{\"name\": \"_row_key\", \"type\": \"string\"},"
        + "{\"name\": \"non_pii_col\", \"type\": \"string\"}]},";
    Schema expectedSchema = new Schema.Parser().parse(schemaStr);
    GenericRecord rec = new GenericData.Record(new Schema.Parser().parse(EXAMPLE_SCHEMA));
    rec.put("_row_key", "key1");
    rec.put("non_pii_col", "val1");
    rec.put("pii_col", "val2");
    rec.put("timestamp", 3.5);
    GenericRecord rec1 = HoodieAvroUtils.removeFields(rec, Collections.singleton("pii_col"));
    assertEquals("key1", rec1.get("_row_key"));
    assertEquals("val1", rec1.get("non_pii_col"));
    assertEquals(3.5, rec1.get("timestamp"));
    if (HoodieAvroUtils.gteqAvro1_10()) {
      GenericRecord finalRec1 = rec1;
      assertThrows(AvroRuntimeException.class, () -> finalRec1.get("pii_col"));
    } else {
      assertNull(rec1.get("pii_col"));
    }
    assertEquals(expectedSchema, rec1.getSchema());

    // non-partitioned table test with empty list of fields.
    schemaStr = "{\"type\": \"record\",\"name\": \"testrec\",\"fields\": [ "
        + "{\"name\": \"timestamp\",\"type\": \"double\"},{\"name\": \"_row_key\", \"type\": \"string\"},"
        + "{\"name\": \"non_pii_col\", \"type\": \"string\"},"
        + "{\"name\": \"pii_col\", \"type\": \"string\"}]},";
    expectedSchema = new Schema.Parser().parse(schemaStr);
    rec1 = HoodieAvroUtils.removeFields(rec, Collections.singleton(""));
    assertEquals(expectedSchema, rec1.getSchema());
  }

  @Test
  public void testGetRootLevelFieldName() {
    assertEquals("a", HoodieAvroUtils.getRootLevelFieldName("a.b.c"));
    assertEquals("a", HoodieAvroUtils.getRootLevelFieldName("a"));
    assertEquals("", HoodieAvroUtils.getRootLevelFieldName(""));
  }

  @Test
  public void testGetNestedFieldVal() {
    GenericRecord rec = new GenericData.Record(new Schema.Parser().parse(EXAMPLE_SCHEMA));
    rec.put("_row_key", "key1");
    rec.put("non_pii_col", "val1");
    rec.put("pii_col", "val2");

    Object rowKey = HoodieAvroUtils.getNestedFieldVal(rec, "_row_key", true, false);
    assertEquals("key1", rowKey);

    Object rowKeyNotExist = HoodieAvroUtils.getNestedFieldVal(rec, "fake_key", true, false);
    assertNull(rowKeyNotExist);

    // Field does not exist
    assertEquals("fake_key(Part -fake_key) field not found in record. Acceptable fields were :[timestamp, _row_key, non_pii_col, pii_col]",
        assertThrows(HoodieException.class, () ->
            HoodieAvroUtils.getNestedFieldVal(rec, "fake_key", false, false)).getMessage());

    // Field exists while value not
    assertNull(HoodieAvroUtils.getNestedFieldVal(rec, "timestamp", false, false));
  }

  @Test
  public void testGetNestedFieldValWithNestedField() {
    Schema nestedSchema = new Schema.Parser().parse(SCHEMA_WITH_NESTED_FIELD);
    GenericRecord rec = new GenericData.Record(nestedSchema);

    // test get .
    assertEquals(". field not found in record. Acceptable fields were :[firstname, lastname, student]",
        assertThrows(HoodieException.class, () ->
            HoodieAvroUtils.getNestedFieldVal(rec, ".", false, false)).getMessage());

    // test get fake_key
    assertEquals("fake_key(Part -fake_key) field not found in record. Acceptable fields were :[firstname, lastname, student]",
        assertThrows(HoodieException.class, () ->
            HoodieAvroUtils.getNestedFieldVal(rec, "fake_key", false, false)).getMessage());

    // test get student(null)
    assertNull(HoodieAvroUtils.getNestedFieldVal(rec, "student", false, false));

    // test get student
    GenericRecord studentRecord = new GenericData.Record(rec.getSchema().getField("student").schema());
    studentRecord.put("firstname", "person");
    rec.put("student", studentRecord);
    assertEquals(studentRecord, HoodieAvroUtils.getNestedFieldVal(rec, "student", false, false));

    // test get student.fake_key
    assertEquals("student.fake_key(Part -fake_key) field not found in record. Acceptable fields were :[firstname, lastname]",
        assertThrows(HoodieException.class, () ->
            HoodieAvroUtils.getNestedFieldVal(rec, "student.fake_key", false, false)).getMessage());

    // test get student.firstname
    assertEquals("person", HoodieAvroUtils.getNestedFieldVal(rec, "student.firstname", false, false));

    // test get student.lastname(null)
    assertNull(HoodieAvroUtils.getNestedFieldVal(rec, "student.lastname", false, false));

    // test get student.firstname.fake_key
    assertEquals("Cannot find a record at part value :firstname",
        assertThrows(HoodieException.class, () ->
            HoodieAvroUtils.getNestedFieldVal(rec, "student.firstname.fake_key", false, false)).getMessage());

    // test get student.lastname(null).fake_key
    assertEquals("Cannot find a record at part value :lastname",
        assertThrows(HoodieException.class, () ->
            HoodieAvroUtils.getNestedFieldVal(rec, "student.lastname.fake_key", false, false)).getMessage());
  }

  @Test
  public void testGetNestedFieldValWithDecimalField() {
    GenericRecord rec = new GenericData.Record(new Schema.Parser().parse(SCHEMA_WITH_DECIMAL_FIELD));
    rec.put("key_col", "key");
    BigDecimal bigDecimal = new BigDecimal("1234.5678");
    ByteBuffer byteBuffer = ByteBuffer.wrap(bigDecimal.unscaledValue().toByteArray());
    rec.put("decimal_col", byteBuffer);

    Object decimalCol = HoodieAvroUtils.getNestedFieldVal(rec, "decimal_col", true, false);
    assertEquals(bigDecimal, decimalCol);

    Object obj = rec.get(1);
    assertTrue(obj instanceof ByteBuffer);
    ByteBuffer buffer = (ByteBuffer) obj;
    assertEquals(0, buffer.position());
  }

  @Test
  public void testGetNestedFieldSchema() throws IOException {
    Schema schema = SchemaTestUtil.getEvolvedSchema();
    GenericRecord rec = new GenericData.Record(schema);
    rec.put("field1", "key1");
    rec.put("field2", "val1");
    rec.put("name", "val2");
    rec.put("favorite_number", 2);
    // test simple field schema
    assertEquals(Schema.create(Schema.Type.STRING), getNestedFieldSchemaFromWriteSchema(rec.getSchema(), "field1"));

    GenericRecord rec2 = new GenericData.Record(schema);
    rec2.put("field1", "key1");
    rec2.put("field2", "val1");
    rec2.put("name", "val2");
    rec2.put("favorite_number", 12);
    // test comparison of non-string type
    assertEquals(-1, GenericData.get().compare(rec.get("favorite_number"), rec2.get("favorite_number"), getNestedFieldSchemaFromWriteSchema(rec.getSchema(), "favorite_number")));

    // test nested field schema
    Schema nestedSchema = new Schema.Parser().parse(SCHEMA_WITH_NESTED_FIELD);
    GenericRecord rec3 = new GenericData.Record(nestedSchema);
    rec3.put("firstname", "person1");
    rec3.put("lastname", "person2");
    GenericRecord studentRecord = new GenericData.Record(rec3.getSchema().getField("student").schema());
    studentRecord.put("firstname", "person1");
    studentRecord.put("lastname", "person2");
    rec3.put("student", studentRecord);

    assertEquals(Schema.create(Schema.Type.STRING), getNestedFieldSchemaFromWriteSchema(rec3.getSchema(), "student.firstname"));
    assertEquals(Schema.create(Schema.Type.STRING), getNestedFieldSchemaFromWriteSchema(nestedSchema, "student.firstname"));
  }

  @Test
  public void testReWriteAvroRecordWithNewSchema() {
    Schema nestedSchema = new Schema.Parser().parse(SCHEMA_WITH_NESTED_FIELD);
    GenericRecord rec3 = new GenericData.Record(nestedSchema);
    rec3.put("firstname", "person1");
    rec3.put("lastname", "person2");
    GenericRecord studentRecord = new GenericData.Record(rec3.getSchema().getField("student").schema());
    studentRecord.put("firstname", "person1");
    studentRecord.put("lastname", "person2");
    rec3.put("student", studentRecord);

    Schema nestedSchemaRename = new Schema.Parser().parse(SCHEMA_WITH_NESTED_FIELD_RENAMED);
    Map<String, String> colRenames = new HashMap<>();
    colRenames.put("fn", "firstname");
    colRenames.put("ln", "lastname");
    colRenames.put("ss", "student");
    colRenames.put("ss.fn", "firstname");
    colRenames.put("ss.ln", "lastname");
    GenericRecord studentRecordRename = HoodieAvroUtils.rewriteRecordWithNewSchema(rec3, nestedSchemaRename, colRenames);
    Assertions.assertEquals(GenericData.get().validate(nestedSchemaRename, studentRecordRename), true);
  }

  @Test
  public void testConvertDaysToDate() {
    Date now = new Date(System.currentTimeMillis());
    int days = HoodieAvroUtils.fromJavaDate(now);
    assertEquals(now.toLocalDate(), HoodieAvroUtils.toJavaDate(days).toLocalDate());
  }

  @Test
  public void testSanitizeName() {
    assertEquals("__23456", sanitizeName("123456"));
    assertEquals("abcdef", sanitizeName("abcdef"));
    assertEquals("_1", sanitizeName("_1"));
    assertEquals("a*bc", sanitizeName("a.bc", "*"));
    assertEquals("abcdef___", sanitizeName("abcdef_."));
    assertEquals("__ab__cd__", sanitizeName("1ab*cd?"));
  }

  @Test
  public void testGenerateProjectionSchema() {
    Schema originalSchema = HoodieAvroUtils.addMetadataFields(new Schema.Parser().parse(EXAMPLE_SCHEMA));

    Schema schema1 = HoodieAvroUtils.generateProjectionSchema(originalSchema, Arrays.asList("_row_key", "timestamp"));
    assertEquals(2, schema1.getFields().size());
    List<String> fieldNames1 = schema1.getFields().stream().map(Schema.Field::name).collect(Collectors.toList());
    assertTrue(fieldNames1.contains("_row_key"));
    assertTrue(fieldNames1.contains("timestamp"));

    assertTrue(assertThrows(HoodieException.class, () ->
        HoodieAvroUtils.generateProjectionSchema(originalSchema, Arrays.asList("_row_key", "timestamp", "fake_field")))
        .getMessage().contains("Field fake_field not found in log schema. Query cannot proceed!"));
  }

  @Test
  public void testWrapAndUnwrapAvroValues() throws IOException {
    Schema schema = new Schema.Parser().parse(SCHEMA_WITH_AVRO_TYPES);
    GenericRecord record = new GenericData.Record(schema);
    Map<String, Class> expectedWrapperClass = new HashMap<>();

    record.put("booleanField", true);
    expectedWrapperClass.put("booleanField", BooleanWrapper.class);
    record.put("intField", 698);
    expectedWrapperClass.put("intField", IntWrapper.class);
    record.put("longField", 192485030493L);
    expectedWrapperClass.put("longField", LongWrapper.class);
    record.put("floatField", 18.125f);
    expectedWrapperClass.put("floatField", FloatWrapper.class);
    record.put("doubleField", 94385932.342104);
    expectedWrapperClass.put("doubleField", DoubleWrapper.class);
    record.put("bytesField", ByteBuffer.wrap(new byte[] {1, 20, 0, 60, 2, 108}));
    expectedWrapperClass.put("bytesField", BytesWrapper.class);
    record.put("stringField", "abcdefghijk");
    expectedWrapperClass.put("stringField", StringWrapper.class);
    record.put("decimalField", ByteBuffer.wrap("9213032.4966".getBytes()));
    expectedWrapperClass.put("decimalField", BytesWrapper.class);
    record.put("timeMillisField", 57996136);
    expectedWrapperClass.put("timeMillisField", IntWrapper.class);
    record.put("timeMicrosField", 57996136930L);
    expectedWrapperClass.put("timeMicrosField", LongWrapper.class);
    record.put("timestampMillisField", 1690828731156L);
    expectedWrapperClass.put("timestampMillisField", LongWrapper.class);
    record.put("timestampMicrosField", 1690828731156982L);
    expectedWrapperClass.put("timestampMicrosField", LongWrapper.class);
    record.put("localTimestampMillisField", 1690828731156L);
    expectedWrapperClass.put("localTimestampMillisField", LongWrapper.class);
    record.put("localTimestampMicrosField", 1690828731156982L);
    expectedWrapperClass.put("localTimestampMicrosField", LongWrapper.class);

    GenericDatumWriter<GenericRecord> writer = new GenericDatumWriter<>(schema);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(baos, null);
    writer.write(record, encoder);
    encoder.flush();
    byte[] data = baos.toByteArray();

    GenericDatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
    BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(data, 0, data.length, null);
    GenericRecord deserializedRecord = reader.read(null, decoder);
    Map<String, Object> fieldValueMapping = deserializedRecord.getSchema().getFields().stream()
        .collect(Collectors.toMap(
            Schema.Field::name,
            field -> deserializedRecord.get(field.name())
        ));

    for (String fieldName : fieldValueMapping.keySet()) {
      Object value = fieldValueMapping.get(fieldName);
      Object wrapperValue = wrapValueIntoAvro((Comparable) value);
      assertTrue(expectedWrapperClass.get(fieldName).isInstance(wrapperValue));
      if (value instanceof Utf8) {
        assertEquals(value.toString(), ((GenericRecord) wrapperValue).get(0));
        assertEquals(value.toString(), unwrapAvroValueWrapper(wrapperValue));
      } else {
        assertEquals(value, ((GenericRecord) wrapperValue).get(0));
        assertEquals(value, unwrapAvroValueWrapper(wrapperValue));
      }
    }
  }

  public static Stream<Arguments> javaValueParams() {
    Object[][] data =
        new Object[][] {
            {new Timestamp(1690766971000L), TimestampMicrosWrapper.class},
            {new Date(1672560000000L), DateWrapper.class},
            {LocalDate.of(2023, 1, 1), DateWrapper.class},
            {new BigDecimal("12345678901234.2948"), DecimalWrapper.class}
        };
    return Stream.of(data).map(Arguments::of);
  }

  @ParameterizedTest
  @MethodSource("javaValueParams")
  public void testWrapAndUnwrapJavaValues(Comparable value, Class expectedWrapper) {
    Object wrapperValue = wrapValueIntoAvro(value);
    assertTrue(expectedWrapper.isInstance(wrapperValue));
    if (value instanceof Timestamp) {
      assertEquals(((Timestamp) value).getTime() * 1000L,
          ((GenericRecord) wrapperValue).get(0));
      assertEquals(((Timestamp) value).getTime(),
          ((Instant) unwrapAvroValueWrapper(wrapperValue)).toEpochMilli());
    } else if (value instanceof Date) {
      assertEquals((int) ChronoUnit.DAYS.between(
              LocalDate.ofEpochDay(0), ((Date) value).toLocalDate()),
          ((GenericRecord) wrapperValue).get(0));
      assertEquals(((Date) value).toLocalDate(), unwrapAvroValueWrapper(wrapperValue));
    } else if (value instanceof LocalDate) {
      assertEquals((int) ChronoUnit.DAYS.between(LocalDate.ofEpochDay(0), (LocalDate) value),
          ((GenericRecord) wrapperValue).get(0));
      assertEquals(value, unwrapAvroValueWrapper(wrapperValue));
    } else {
      assertEquals("0.000000000000000",
          ((BigDecimal) value)
              .subtract((BigDecimal) unwrapAvroValueWrapper(wrapperValue)).toPlainString());
    }
  }

  @Test
  public void testAddMetadataFields() {
    Schema baseSchema = new Schema.Parser().parse(EXAMPLE_SCHEMA_WITH_PROPS);
    Schema schemaWithMetadata = HoodieAvroUtils.addMetadataFields(baseSchema);
    List<Schema.Field> updatedFields = schemaWithMetadata.getFields();
    // assert fields added in expected order
    assertEquals(HoodieRecord.COMMIT_TIME_METADATA_FIELD, updatedFields.get(0).name());
    assertEquals(HoodieRecord.COMMIT_SEQNO_METADATA_FIELD, updatedFields.get(1).name());
    assertEquals(HoodieRecord.RECORD_KEY_METADATA_FIELD, updatedFields.get(2).name());
    assertEquals(HoodieRecord.PARTITION_PATH_METADATA_FIELD, updatedFields.get(3).name());
    assertEquals(HoodieRecord.FILENAME_METADATA_FIELD, updatedFields.get(4).name());
    // assert original fields are copied over
    List<Schema.Field> originalFieldsInUpdatedSchema = updatedFields.subList(5, updatedFields.size());
    assertEquals(baseSchema.getFields(), originalFieldsInUpdatedSchema);
    // validate properties are properly copied over
    assertEquals("custom_schema_property_value", schemaWithMetadata.getProp("custom_schema_property"));
    assertEquals("value", originalFieldsInUpdatedSchema.get(0).getProp("custom_field_property"));
  }

  @Test
  void testSafeAvroToJsonStringMissingRequiredField() {
    Schema schema = new Schema.Parser().parse(EXAMPLE_SCHEMA);
    GenericRecord record = new GenericData.Record(schema);
    record.put("non_pii_col", "val1");
    record.put("pii_col", "val2");
    record.put("timestamp", 3.5);
    String jsonString = HoodieAvroUtils.safeAvroToJsonString(record);
    assertEquals("{\"timestamp\": 3.5, \"_row_key\": null, \"non_pii_col\": \"val1\", \"pii_col\": \"val2\"}", jsonString);
  }

  @Test
  void testSafeAvroToJsonStringBadDataType() {
    Schema schema = new Schema.Parser().parse(EXAMPLE_SCHEMA);
    GenericRecord record = new GenericData.Record(schema);
    record.put("non_pii_col", "val1");
    record.put("_row_key", "key");
    record.put("pii_col", "val2");
    record.put("timestamp", "foo");
    String jsonString = HoodieAvroUtils.safeAvroToJsonString(record);
    assertEquals("{\"timestamp\": \"foo\", \"_row_key\": \"key\", \"non_pii_col\": \"val1\", \"pii_col\": \"val2\"}", jsonString);
  }

  @Test
  void testConvertBytesToFixed() {
    Random rand = new Random();
    //size calculated using AvroInternalSchemaConverter.computeMinBytesForPrecision
    testConverBytesToFixedHelper(rand.nextDouble(), 13, 7, 6);
    testConverBytesToFixedHelper(rand.nextDouble(), 4, 2, 2);
    testConverBytesToFixedHelper(rand.nextDouble(), 32, 12, 14);
  }

  private static void testConverBytesToFixedHelper(double value, int precision, int scale, int size) {
    BigDecimal decfield = BigDecimal.valueOf(value * Math.pow(10, precision - scale))
        .setScale(scale, RoundingMode.HALF_UP).round(new MathContext(precision, RoundingMode.HALF_UP));
    byte[] encodedDecimal = decfield.unscaledValue().toByteArray();
    Schema fixedSchema = new Schema.Parser().parse("{\"type\": \"record\",\"name\": \"testrec\",\"fields\": [{\"name\": \"decfield\", \"type\": {\"type\": \"fixed\", \"name\": \"idk\","
        + " \"logicalType\": \"decimal\", \"precision\": " + precision + ", \"scale\": " + scale + ", \"size\": " + size + "}}]}").getFields().get(0).schema();
    GenericData.Fixed fixed = (GenericData.Fixed) HoodieAvroUtils.convertBytesToFixed(encodedDecimal, fixedSchema);
    BigDecimal after = new Conversions.DecimalConversion().fromFixed(fixed, fixedSchema, fixedSchema.getLogicalType());
    assertEquals(decfield, after);
  }

  @Test
  void testHasDecimalField() {
    assertTrue(HoodieAvroUtils.hasDecimalField(new Schema.Parser().parse(SCHEMA_WITH_DECIMAL_FIELD)));
    assertFalse(HoodieAvroUtils.hasDecimalField(new Schema.Parser().parse(EVOLVED_SCHEMA)));
    assertFalse(HoodieAvroUtils.hasDecimalField(new Schema.Parser().parse(SCHEMA_WITH_NON_NULLABLE_FIELD)));
    assertTrue(HoodieAvroUtils.hasDecimalField(HoodieTestDataGenerator.AVRO_SCHEMA));
    assertTrue(HoodieAvroUtils.hasDecimalField(HoodieTestDataGenerator.AVRO_TRIP_ENCODED_DECIMAL_SCHEMA));
    Schema recordWithMapAndArray = Schema.createRecord("recordWithMapAndArray", null, null, false,
        Arrays.asList(new Schema.Field("mapfield", Schema.createMap(Schema.create(Schema.Type.INT)), null, null),
            new Schema.Field("arrayfield", Schema.createArray(Schema.create(Schema.Type.INT)), null, null)
        ));
    assertFalse(HoodieAvroUtils.hasDecimalField(recordWithMapAndArray));
    Schema recordWithDecMapAndArray = Schema.createRecord("recordWithDecMapAndArray", null, null, false,
        Arrays.asList(new Schema.Field("mapfield",
                Schema.createMap(LogicalTypes.decimal(10,6).addToSchema(Schema.create(Schema.Type.BYTES))), null, null),
            new Schema.Field("arrayfield", Schema.createArray(Schema.create(Schema.Type.INT)), null, null)
        ));
    assertTrue(HoodieAvroUtils.hasDecimalField(recordWithDecMapAndArray));
    Schema recordWithMapAndDecArray = Schema.createRecord("recordWithMapAndDecArray", null, null, false,
        Arrays.asList(new Schema.Field("mapfield",
            Schema.createMap(Schema.create(Schema.Type.INT)), null, null), new Schema.Field("arrayfield",
            Schema.createArray(LogicalTypes.decimal(10,6).addToSchema(Schema.create(Schema.Type.BYTES))), null, null)
        ));
    assertTrue(HoodieAvroUtils.hasDecimalField(recordWithMapAndDecArray));
  }

  @Test
  void testHasListOrMapField() {
    Schema nestedList = Schema.createRecord("nestedList", null, null, false, Arrays.asList(
        new Schema.Field("intField", Schema.create(Schema.Type.INT)),
        new Schema.Field("nested", Schema.createRecord("nestedSchema", null, null, false, Collections.singletonList(
            new Schema.Field("listField", Schema.createArray(Schema.create(Schema.Type.INT)))
        )))
    ));
    Schema nestedMap = Schema.createRecord("nestedMap", null, null, false, Arrays.asList(
        new Schema.Field("intField", Schema.create(Schema.Type.INT)),
        new Schema.Field("nested", Schema.createUnion(Schema.create(Schema.Type.NULL),
            Schema.createRecord("nestedSchema", null, null, false,
                Collections.singletonList(new Schema.Field("mapField", Schema.createMap(Schema.create(Schema.Type.INT)))
                ))))
    ));
    assertTrue(HoodieAvroUtils.hasListOrMapField(nestedList));
    assertTrue(HoodieAvroUtils.hasListOrMapField(nestedMap));
    assertFalse(HoodieAvroUtils.hasListOrMapField(new Schema.Parser().parse(EXAMPLE_SCHEMA)));
  }

  @Test
  void testHasSmallPrecisionDecimalField() {
    assertTrue(HoodieAvroUtils.hasSmallPrecisionDecimalField(new Schema.Parser().parse(SCHEMA_WITH_DECIMAL_FIELD)));
    assertFalse(HoodieAvroUtils.hasSmallPrecisionDecimalField(new Schema.Parser().parse(SCHEMA_WITH_AVRO_TYPES)));
    assertFalse(HoodieAvroUtils.hasSmallPrecisionDecimalField(new Schema.Parser().parse(EXAMPLE_SCHEMA)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testGetSortColumnValuesWithPartitionPathAndRecordKey(boolean suffixRecordKey) {
    Schema schema = new Schema.Parser().parse(EXAMPLE_SCHEMA);
    GenericRecord record = new GenericData.Record(schema);
    record.put("non_pii_col", "val1");
    record.put("pii_col", "val2");
    record.put("timestamp", 3.5);
    HoodieRecordPayload avroPayload = new RewriteAvroPayload(record);
    HoodieAvroRecord avroRecord = new HoodieAvroRecord(new HoodieKey("record1", "partition1"), avroPayload);

    String[] userSortColumns = new String[]{"non_pii_col", "timestamp"};
    Object[] sortColumnValues = HoodieAvroUtils.getSortColumnValuesWithPartitionPathAndRecordKey(avroRecord, userSortColumns, Schema.parse(EXAMPLE_SCHEMA), suffixRecordKey, true);
    if (suffixRecordKey) {
      assertArrayEquals(new Object[]{"partition1", "val1", 3.5, "record1"}, sortColumnValues);
    } else {
      assertArrayEquals(new Object[]{"partition1", "val1", 3.5}, sortColumnValues);
    }
  }
}
