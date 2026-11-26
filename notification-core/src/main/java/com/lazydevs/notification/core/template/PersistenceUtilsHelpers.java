package com.lazydevs.notification.core.template;

import freemarker.ext.util.WrapperTemplateModel;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import freemarker.template.TemplateScalarModel;
import lazydevs.mapper.utils.SerDe;
import lazydevs.mapper.utils.engine.ScriptEngines;
import lazydevs.mapper.utils.engine.TemplateEngine;
import lazydevs.mapper.utils.file.FileUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The helpers persistence-utils' {@code TemplateEngine.generate} put into every template model
 * up to 1.2, now added by the notification engine itself (DD-26).
 * <p>
 * {@code uuid} and {@code trim} are always added: they only compute a value from their
 * arguments. {@code eval}, {@code file}, {@code js}, {@code serde_deserializeToMap} and
 * {@code serde_serialize} are added only with {@code notification.template.legacy-helpers=true};
 * they delegate to the same persistence-utils functions as before, so {@code eval} and
 * {@code file} still render through persistence-utils' own FreeMarker configuration.
 * Like persistence-utils, they replace data-model entries of the same name.
 */
final class PersistenceUtilsHelpers {

    /** Names of the helpers that need {@code legacy-helpers=true}. */
    static final List<String> LEGACY_HELPER_NAMES =
            List.of("eval", "file", "js", "serde_deserializeToMap", "serde_serialize");

    private PersistenceUtilsHelpers() {
    }

    /**
     * Add {@code uuid} and {@code trim}, and the legacy helpers when {@code legacy} is set.
     */
    static void addTo(Map<String, Object> model, boolean legacy) {
        model.put("uuid", new Uuid());
        model.put("trim", new Trim());
        if (legacy) {
            model.put("eval", new Eval());
            model.put("file", new FileAsString());
            model.put("js", new JavaScript());
            model.put("serde_deserializeToMap", new DeserializeToMap());
            model.put("serde_serialize", new Serialize());
        }
    }

    private static void requireArguments(List<?> arguments, String method, String what) {
        if (arguments.size() != 1) {
            throw new IllegalArgumentException("Method '" + method + "' requires exactly 1 " + what);
        }
    }

    private static String scalar(Object argument) throws TemplateModelException {
        return ((TemplateScalarModel) argument).getAsString();
    }

    /** ${uuid()}: a random UUID. */
    private static final class Uuid implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) {
            return UUID.randomUUID().toString();
        }
    }

    /** ${trim(text)}: the string with leading and trailing whitespace removed. */
    private static final class Trim implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            requireArguments(arguments, "Trim", "String input");
            return scalar(arguments.get(0)).trim();
        }
    }

    /** ${eval(source)}: renders {@code source} with an empty model through persistence-utils. */
    private static final class Eval implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            requireArguments(arguments, "eval", "String input");
            return TemplateEngine.getInstance().generate(scalar(arguments.get(0)), new HashMap<>());
        }
    }

    /** ${file(path)}: reads {@code path} (file system or {@code classpath:}) and renders it like eval. */
    private static final class FileAsString implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) {
            requireArguments(arguments, "file", "String input");
            String filePath = null;
            try {
                filePath = scalar(arguments.get(0));
                return TemplateEngine.getInstance().generate(FileUtils.readFileAsString(filePath), new HashMap<>());
            } catch (Exception e) {
                throw new IllegalStateException("Error While reading file as string from filePath = " + filePath, e);
            }
        }
    }

    /** ${js(functionName, args...)}: calls a function loaded into persistence-utils' script engine. */
    private static final class JavaScript implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) {
            if (arguments.isEmpty()) {
                throw new IllegalArgumentException(
                        "Method 'js' requires at least 1 String input stating the functionName");
            }
            try {
                String functionName = scalar(arguments.get(0));
                Object[] args = arguments.subList(1, arguments.size()).toArray();
                return ScriptEngines.JAVASCRIPT.invokeFunction(functionName, args);
            } catch (Exception e) {
                throw new IllegalStateException("Error while executing javascript function", e);
            }
        }
    }

    /** ${serde_deserializeToMap(json)}: parses a JSON object into a map. */
    private static final class DeserializeToMap implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) {
            requireArguments(arguments, "serde_deserializeToMap", "String input");
            return SerDe.JSON.deserializeToMap(String.valueOf(arguments.get(0)));
        }
    }

    /** ${serde_serialize(value)}: the value as JSON. */
    private static final class Serialize implements TemplateMethodModelEx {
        @Override
        public Object exec(List arguments) {
            requireArguments(arguments, "serde_serialize", "Object");
            Object value = arguments.get(0);
            if (value instanceof WrapperTemplateModel wrapper) {
                value = wrapper.getWrappedObject();
            }
            return SerDe.JSON.serialize(value);
        }
    }
}
