package com.badoo.hprof.viewer.viewfactory;

import com.badoo.hprof.library.model.BasicType;
import com.badoo.hprof.library.model.ClassDefinition;
import com.badoo.hprof.library.model.InstanceField;
import com.badoo.hprof.viewer.DumpData;

import javax.annotation.Nonnull;

/**
 * Class definition for accessing data of an instance dump of a String
 *
 * Created by Erik Andre on 05/12/15.
 */
class StringClassDef extends BaseClassDef {

    final ClassDefinition cls;
    final InstanceField value;
    final InstanceField offset;
    final InstanceField count;

    public StringClassDef(@Nonnull DumpData data) {
        cls = findClassByName("java.lang.String", data);
        value = findFieldByName("value", BasicType.OBJECT, cls, data);
        offset = findFieldByName("offset", BasicType.INT, cls, data);
        count = findFieldByName("count", BasicType.INT, cls, data);
    }
}
