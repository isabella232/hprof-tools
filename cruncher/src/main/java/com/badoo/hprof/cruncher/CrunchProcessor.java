package com.badoo.hprof.cruncher;

import com.badoo.hprof.cruncher.bmd.BmdTag;
import com.badoo.hprof.cruncher.bmd.DataWriter;
import com.badoo.hprof.cruncher.util.CodingUtil;
import com.badoo.hprof.library.HprofReader;
import com.badoo.hprof.library.Tag;
import com.badoo.hprof.library.heap.HeapDumpReader;
import com.badoo.hprof.library.heap.HeapTag;
import com.badoo.hprof.library.heap.processor.HeapDumpDiscardProcessor;
import com.badoo.hprof.library.model.BasicType;
import com.badoo.hprof.library.model.ClassDefinition;
import com.badoo.hprof.library.model.ConstantField;
import com.badoo.hprof.library.model.HprofString;
import com.badoo.hprof.library.model.Instance;
import com.badoo.hprof.library.model.InstanceField;
import com.badoo.hprof.library.model.StaticField;
import com.badoo.hprof.library.processor.DiscardProcessor;
import com.google.common.io.CountingInputStream;
import com.sun.istack.internal.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.badoo.hprof.library.util.StreamUtil.read;
import static com.badoo.hprof.library.util.StreamUtil.readInt;
import static com.badoo.hprof.library.util.StreamUtil.writeInt;

/**
 * Processor for reading a HPROF file and outputting a BMD file.
 * <p/>
 * Created by Erik Andre on 22/10/14.
 */
public class CrunchProcessor extends DiscardProcessor {

    private class CrunchBdmWriter extends DataWriter {

        protected CrunchBdmWriter(OutputStream out) {
            super(out);
        }

        public void writeHeader(int version, @Nullable byte[] metadata) throws IOException {
            writeInt32(version);
            writeByteArrayWithLength(metadata != null ? metadata : new byte[]{});
        }

        public void writeString(HprofString string, boolean hashed) throws IOException {
            writeInt32(hashed ? BmdTag.HASHED_STRING : BmdTag.STRING);
            writeInt32(string.getId());
            if (hashed) {
                writeInt32(string.getValue().hashCode());
            }
            else {
                writeByteArrayWithLength(string.getValue().getBytes());
            }
        }

        public void writeLegacyRecord(int tag, byte[] data) throws IOException {
            writeInt32(BmdTag.LEGACY_HPROF_RECORD);
            writeInt32(tag);
            writeRawBytes(data);
        }

        public void writeClassDefinition(ClassDefinition classDef) throws IOException {
            writeInt32(BmdTag.CLASS_DEFINITION);
            writeInt32(mapObjectId(classDef.getObjectId()));
            writeInt32(mapObjectId(classDef.getSuperClassObjectId()));
            // Write constants and static fields (not filtered)
            int constantFieldCount = classDef.getConstantFields().size();
            writeInt32(constantFieldCount);
            for (int i = 0; i < constantFieldCount; i++) {
                ConstantField field = classDef.getConstantFields().get(i);
                writeInt32(field.getPoolIndex());
                writeInt32(field.getType().type);
                writeFieldValue(field.getType(), field.getValue());
            }
            int staticFieldCount = classDef.getStaticFields().size();
            writeInt32(staticFieldCount);
            for (int i = 0; i < staticFieldCount; i++) {
                StaticField field = classDef.getStaticFields().get(i);
                writeInt32(stringIds.get(field.getFieldNameId()));
                writeInt32(field.getType().type);
                writeFieldValue(field.getType(), field.getValue());
            }
            // Filter instance fields before writing them
            int skippedFieldSize = 0;
            int instanceFieldCount = classDef.getInstanceFields().size();
            for (int i = 0; i < instanceFieldCount; i++) {
                InstanceField field = classDef.getInstanceFields().get(i);
                if (field.getType() != BasicType.OBJECT) {
                    skippedFieldSize += field.getType().size;
                    continue;
                }
                writeInt32(stringIds.get(field.getFieldNameId()));
                writeInt32(field.getType().type);
            }
            writeInt32(0); // End marker for instance fields
            writeInt32(skippedFieldSize);
        }

        public void writeInstanceDump(Instance instance) throws IOException {
            writeInt32(BmdTag.INSTANCE_DUMP);
            writeInt32(mapObjectId(instance.getObjectId()));
            writeInt32(mapObjectId(instance.getClassObjectId()));
            ClassDefinition currentClass = classesByOriginalId.get(instance.getClassObjectId());
            ByteArrayInputStream in = new ByteArrayInputStream(instance.getInstanceFieldData());
            while (currentClass != null) {
                int fieldCount = currentClass.getInstanceFields().size();
                for (int i = 0; i < fieldCount; i++) {
                    InstanceField field = currentClass.getInstanceFields().get(i);
                    BasicType type = field.getType();
                    if (type == BasicType.OBJECT) {
                        int id = readInt(in);
                        writeInt32(mapObjectId(id));
                    } else { // Other fields are ignored
                        in.skip(type.size);
                    }
                }
                currentClass = classesByOriginalId.get(currentClass.getSuperClassObjectId());
            }
            if (in.available() != 0) {
                throw new IllegalStateException("Did not read the expected number of bytes. Available: " + in.available());
            }
        }

        private void writeFieldValue(BasicType type, byte[] data) throws IOException {
            switch (type) {
                case OBJECT:
                    writeInt32(mapObjectId(CodingUtil.readInt(data)));
                    break;
                case SHORT:
                    writeInt32(CodingUtil.readShort(data));
                    break;
                case INT:
                    writeInt32(CodingUtil.readInt(data));
                    break;
                case LONG:
                    writeInt64(CodingUtil.readLong(data));
                    break;
                case FLOAT:
                    writeFloat(Float.intBitsToFloat(CodingUtil.readInt(data)));
                    break;
                case DOUBLE:
                    writeDouble(Double.longBitsToDouble(CodingUtil.readLong(data)));
                    break;
                case BOOLEAN:
                    writeRawBytes(data);
                    break;
                case BYTE:
                    writeRawBytes(data);
                    break;
                case CHAR:
                    writeRawBytes(data);
                    break;
            }
        }
    }

    private class ClassDumpProcessor extends HeapDumpDiscardProcessor {

        @Override
        public void onHeapRecord(int tag, HeapDumpReader reader) throws IOException {
            switch (tag) {
                case HeapTag.CLASS_DUMP:
                    ClassDefinition def = reader.readClassDumpRecord(classesByOriginalId);
                    writer.writeClassDefinition(def);
                    break;
                // TODO: Write root records!
                default:
                    super.onHeapRecord(tag, reader);
            }
        }

    }

    private class ObjectDumpProcessor extends HeapDumpDiscardProcessor {

        @Override
        public void onHeapRecord(int tag, HeapDumpReader reader) throws IOException {
            switch (tag) {
                case HeapTag.INSTANCE_DUMP:
                    Instance instance = reader.readInstanceDump();
                    writer.writeInstanceDump(instance);
                    break;
                case HeapTag.OBJECT_ARRAY_DUMP:
                    super.onHeapRecord(tag, reader);
                    break;
                case HeapTag.PRIMITIVE_ARRAY_DUMP:
                    super.onHeapRecord(tag, reader);
                    break;
                default:
                    super.onHeapRecord(tag, reader);
            }
        }

    }

    private final CrunchBdmWriter writer;
    private int nextStringId = 1; // Skipping 0 since this is used as a marker in some cases
    private Map<Integer, Integer> stringIds = new HashMap<Integer, Integer>(); // Maps original to updated string ids
    private int nextObjectId = 1;
    private Set<Integer> mappedIds = new HashSet<Integer>();
    private Map<Integer, Integer> objectIds = new HashMap<Integer, Integer>(); // Maps original to updated object/class ids
    private Map<Integer, ClassDefinition> classesByOriginalId = new HashMap<Integer, ClassDefinition>(); // Maps original class id to the class definition
    private Map<Integer, ClassDefinition> classesByUpdatedId = new HashMap<Integer, ClassDefinition>(); // Maps updated class id to the class definition
    private boolean readObjects;

    public CrunchProcessor(OutputStream out) {
        this.writer = new CrunchBdmWriter(out);
    }

    public void allClassesRead() {
        readObjects = true;
    }

    @Override
    public void onRecord(int tag, int timestamp, int length, HprofReader reader) throws IOException {
        if (!readObjects) { // 1st pass: read class definitions and strings
            switch (tag) {
                case Tag.STRING:
                    HprofString string = reader.readStringRecord(length, timestamp);
                    // We replace the original string id with one starting from 0 as these are more efficient to store
                    stringIds.put(string.getId(), nextStringId); // Save the original id so we can update references later
                    string.setId(nextStringId);
                    nextStringId++;
                    writer.writeString(string, true);
                    break;
                case Tag.LOAD_CLASS:
                    ClassDefinition classDef = reader.readLoadClassRecord();
                    classesByOriginalId.put(classDef.getObjectId(), classDef);
                    int updatedId = mapObjectId(classDef.getObjectId());
                    classesByUpdatedId.put(updatedId, classDef);
                    break;
                case Tag.HEAP_DUMP:
                case Tag.HEAP_DUMP_SEGMENT:
                    ClassDumpProcessor dumpProcessor = new ClassDumpProcessor();
                    HeapDumpReader dumpReader = new HeapDumpReader(reader.getInputStream(), length, dumpProcessor);
                    while (dumpReader.hasNext()) {
                        dumpReader.next();
                    }
                    break;
                case Tag.UNLOAD_CLASS:
                case Tag.HEAP_DUMP_END:
                    super.onRecord(tag, timestamp, length, reader); // These records can be discarded
                    break;
                default:
                    byte[] data = read(reader.getInputStream(), length);
                    writer.writeLegacyRecord(tag, data);
                    break;
            }
        }
        else { // 2nd pass: read object dumps
            switch (tag) {
                case Tag.HEAP_DUMP:
                case Tag.HEAP_DUMP_SEGMENT:
                    ObjectDumpProcessor dumpProcessor = new ObjectDumpProcessor();
                    HeapDumpReader dumpReader = new HeapDumpReader(reader.getInputStream(), length, dumpProcessor);
                    while (dumpReader.hasNext()) {
                        dumpReader.next();
                    }
                    break;
                default:
                    super.onRecord(tag, timestamp, length, reader); // Skip record
            }
        }
    }

    @Override
    public void onHeader(String text, int idSize, int timeHigh, int timeLow) throws IOException {
        writer.writeHeader(1, text.getBytes());
    }

    private int mapObjectId(int id) {
        if (!objectIds.containsKey(id)) {
            mappedIds.add(nextObjectId);
            objectIds.put(id, nextObjectId);
            nextObjectId++;
        }
        if (mappedIds.contains(id)) {
            throw new IllegalArgumentException("Trying to map an already mapped id! " + id);
        }
        return objectIds.get(id);
    }
}
