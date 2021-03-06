/*
 * Copyright (C) 2016 volders GmbH with <3 in Berlin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package berlin.volders.auto.value.map.proc;

import com.google.auto.value.extension.AutoValueExtension;
import com.google.auto.value.processor.escapevelocity.Template;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import berlin.volders.auto.value.map.MapKey;

@SuppressWarnings("unused")
public final class AutoValueMapExtension extends AutoValueExtension {

    @Override
    public boolean applicable(Context context) {
        ProcessingEnvironment procEnv = context.processingEnvironment();
        Types types = procEnv.getTypeUtils();
        DeclaredType mapType = createMapType(types, procEnv.getElementUtils());
        return types.isAssignable(context.autoValueClass().asType(), mapType);
    }

    private static DeclaredType createMapType(Types types, Elements elements) {
        TypeElement objectType = elements.getTypeElement(Object.class.getName());
        TypeElement stringType = elements.getTypeElement(String.class.getName());
        TypeElement mapType = elements.getTypeElement(Map.class.getName());
        return types.getDeclaredType(mapType, stringType.asType(), objectType.asType());
    }

    @Override
    public Set<ExecutableElement> consumeMethods(Context context) {
        Elements elements = context.processingEnvironment().getElementUtils();
        TypeElement mapType = elements.getTypeElement(Map.class.getName());
        HashSet<ExecutableElement> methods = new HashSet<>();
        // the map interface defines equals, hashCode and default methods; ignore these
        for (Element element : mapType.getEnclosedElements()) {
            if (element instanceof ExecutableElement && consumeMethod((ExecutableElement) element)) {
                methods.add((ExecutableElement) element);
            }
        }
        return Collections.unmodifiableSet(methods);
    }

    private static boolean consumeMethod(ExecutableElement method) {
        switch (method.getSimpleName().toString()) {
            case "clear":
            case "containsKey":
            case "containsValue":
            case "entrySet":
            case "get":
            case "isEmpty":
            case "keySet":
            case "put":
            case "putAll":
            case "size":
            case "values":
                return true;
            case "remove":
                return method.getParameters().size() == 1;
            default:
                return false;
        }
    }

    @Override
    public String generateClass(Context context, String className, String classToExtend, boolean isFinal) {
        try {
            HashMap<String, Object> vars = new HashMap<>();
            vars.put("packageName", context.packageName());
            vars.put("className", className);
            vars.put("typeParameters", getTypeParameters(context));
            vars.put("classToExtend", classToExtend);
            vars.put("isFinal", isFinal);
            vars.put("properties", createProperties(context));
            InputStream in = getClass().getResourceAsStream("auto-value-map.vm");
            Reader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            return Template.parseFrom(reader).evaluate(vars);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String getTypeParameters(Context context) {
        List<?> params = context.autoValueClass().getTypeParameters();
        return params.size() == 0 ? "" : "<" + params + ">";
    }

    private static List<Property> createProperties(Context context) {
        ArrayList<Property> properties = new ArrayList<>();
        for (Map.Entry<String, ExecutableElement> entry : context.properties().entrySet()) {
            properties.add(buildProperty(entry.getKey(), entry.getValue()).create());
        }
        return properties;
    }

    private static Property.Builder buildProperty(String name, ExecutableElement value) {
        Property.Builder property = new AutoValue_Property.Builder()
                .setKey(name)
                .setName(name)
                .setValue(value.toString())
                .setType(value.getReturnType().toString())
                .setNullable("");
        return addAnnotatedFeatures(value, property);
    }

    private static Property.Builder addAnnotatedFeatures(ExecutableElement value, Property.Builder property) {
        String key = null;
        for (AnnotationMirror annotation : value.getAnnotationMirrors()) {
            String valueName = null;
            String name = annotation.getAnnotationType().asElement().getSimpleName().toString();
            switch (name) {
                case "Nullable":
                    name = annotation.getAnnotationType().asElement().toString();
                    property.setNullable('@' + name + ' ');
                    break;
                case "Json":
                    valueName = "name";
                case "Field":
                case "Header":
                case "Part":
                case "Query":
                case "SerializedName":
                    name = getAnnotationValue(annotation, valueName);
                    if (name != null) {
                        key = name.trim();
                    }
                    break;
            }
        }
        MapKey mapKey = value.getAnnotation(MapKey.class);
        if (mapKey != null) {
            key = mapKey.value().trim();
        }
        if (key == null || key.isEmpty()) {
            return property;
        }
        return property.setKey(key);
    }

    private static String getAnnotationValue(AnnotationMirror annotation, String name) {
        if (name == null) {
            name = "value";
        }
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> value :
                annotation.getElementValues().entrySet()) {
            if (name.equals(value.getKey().getSimpleName().toString())) {
                return value.getValue().getValue().toString();
            }
        }
        return null;
    }
}
