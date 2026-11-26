package com.lazydevs.notification.core.template;

import freemarker.core.Environment;
import freemarker.template.AdapterTemplateModel;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import freemarker.template.TemplateHashModel;
import freemarker.template.TemplateModel;
import freemarker.template.TemplateModelException;
import freemarker.template.TemplateSequenceModel;
import freemarker.ext.util.WrapperTemplateModel;

import java.io.Writer;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template exception handler that works out which data-model value broke a render, then
 * rethrows the exception unchanged (RETHROW semantics, nothing is written to the output).
 * <p>
 * FreeMarker blames an expression such as {@code u.name}. When {@code u} is a
 * {@code #function} or {@code #macro} parameter, that is not a path the caller knows, so
 * the handler, which runs while the failing macro context is still active, evaluates the
 * blamed expression step by step and looks for each step's value, by identity, in the data
 * model. The deepest step found there is rebased onto its data-model path, giving
 * {@code user.name} for {@code <@card u=user/>}. Values that are not in the data model
 * (template-local variables) keep the blamed expression as their path.
 * <p>
 * The result is stored as a custom attribute on the {@link Environment}, keyed to the
 * exception, and read with {@link #variablePath} after processing fails.
 */
final class VariablePathRecorder implements TemplateExceptionHandler {

    private static final String ATTRIBUTE = VariablePathRecorder.class.getName();
    private static final String MODEL_ATTRIBUTE = ATTRIBUTE + ".model";

    /** Bounds on the identity search through the data model, which only runs on failure. */
    private static final int MAX_DEPTH = 16;
    private static final int MAX_NODES = 10_000;

    private static final Pattern ROOT = Pattern.compile("[\\p{L}_$@][\\p{L}\\p{N}_$@]*");
    private static final Pattern STEP = Pattern.compile(
            "\\.([\\p{L}_$@][\\p{L}\\p{N}_$@]*)|\\[(\\d{1,9})]|\\[\"((?:[^\"\\\\]|\\\\.)*)\"]|\\['((?:[^'\\\\]|\\\\.)*)']");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private record Failure(TemplateException exception, String variablePath) {
    }

    @Override
    public void handleTemplateException(TemplateException te, Environment env, Writer out) throws TemplateException {
        env.setCustomAttribute(ATTRIBUTE, new Failure(te, resolve(te.getBlamedExpressionString(), env)));
        throw te;
    }

    /**
     * Make the raw data model of this render available to the handler; call before
     * {@link Environment#process()}.
     */
    static void bind(Environment env, Map<String, Object> dataModel) {
        env.setCustomAttribute(MODEL_ATTRIBUTE, dataModel);
    }

    /**
     * The data-model path recorded for {@code exception} in {@code env}, or {@code null}.
     */
    static String variablePath(Environment env, TemplateException exception) {
        return env.getCustomAttribute(ATTRIBUTE) instanceof Failure failure && failure.exception() == exception
                ? failure.variablePath()
                : null;
    }

    /**
     * Path of the blamed expression, rebased onto the data model; {@code null} when the
     * expression is not a plain variable path ({@code a.b[0]["c"]}) or starts at a
     * template-defined variable whose value is not part of the data model.
     */
    static String resolve(String blamedExpression, Environment env) {
        List<Object> steps = parse(blamedExpression);
        if (steps.isEmpty()) {
            return null;
        }
        Object dataModel = env.getCustomAttribute(MODEL_ATTRIBUTE);
        String root = (String) steps.get(0);
        String rebased = null;
        int rebasedSteps = 0;
        TemplateModel model = variable(env, root);
        for (int i = 0; i < steps.size() && model != null; i++) {
            String found = dataModel instanceof Map<?, ?> rootMap ? find(rootMap, unwrap(model)) : null;
            if (found != null) {
                rebased = found;
                rebasedSteps = i + 1;
            }
            model = i + 1 < steps.size() ? child(model, steps.get(i + 1)) : null;
        }
        if (rebased == null && isTemplateDefined(env, root)) {
            return null;
        }
        StringBuilder path = new StringBuilder(rebased != null ? rebased : root);
        for (int i = rebased != null ? rebasedSteps : 1; i < steps.size(); i++) {
            appendStep(path, steps.get(i));
        }
        return path.toString();
    }

    /**
     * Root variable name followed by {@code String} keys and {@code Integer} indexes, or an
     * empty list when the expression is not a plain variable path.
     */
    static List<Object> parse(String expression) {
        if (expression == null) {
            return List.of();
        }
        String text = expression.strip();
        Matcher root = ROOT.matcher(text);
        if (!root.lookingAt()) {
            return List.of();
        }
        List<Object> steps = new ArrayList<>();
        steps.add(root.group());
        Matcher step = STEP.matcher(text);
        int position = root.end();
        while (position < text.length()) {
            step.region(position, text.length());
            if (!step.lookingAt()) {
                return List.of();
            }
            steps.add(stepValue(step));
            position = step.end();
        }
        return steps;
    }

    private static Object stepValue(Matcher step) {
        if (step.group(1) != null) {
            return step.group(1);
        }
        if (step.group(2) != null) {
            return Integer.valueOf(step.group(2));
        }
        String quoted = step.group(3) != null ? step.group(3) : step.group(4);
        return quoted.replaceAll("\\\\(.)", "$1");
    }

    private static void appendStep(StringBuilder path, Object step) {
        if (step instanceof Integer index) {
            path.append('[').append(index).append(']');
        } else if (IDENTIFIER.matcher((String) step).matches()) {
            path.append('.').append(step);
        } else {
            path.append("[\"").append(((String) step).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"]");
        }
    }

    private static TemplateModel variable(Environment env, String name) {
        try {
            return env.getVariable(name);
        } catch (TemplateModelException _) {
            return null;
        }
    }

    /**
     * Whether {@code name} is a local, namespace or global variable the template set itself,
     * as opposed to a data-model value.
     */
    private static boolean isTemplateDefined(Environment env, String name) {
        try {
            return env.getLocalVariable(name) != null
                    || env.getCurrentNamespace().get(name) != null
                    || env.getGlobalNamespace().get(name) != null;
        } catch (TemplateModelException _) {
            return true;
        }
    }

    private static TemplateModel child(TemplateModel model, Object step) {
        try {
            if (step instanceof Integer index && model instanceof TemplateSequenceModel sequence) {
                return index < sequence.size() ? sequence.get(index) : null;
            }
            if (step instanceof String key && model instanceof TemplateHashModel hash) {
                return hash.get(key);
            }
        } catch (TemplateModelException _) {
            // Treat a value that cannot be read like a missing one
        }
        return null;
    }

    private static Object unwrap(TemplateModel model) {
        if (model instanceof AdapterTemplateModel adapter) {
            return adapter.getAdaptedObject(Object.class);
        }
        return model instanceof WrapperTemplateModel wrapper ? wrapper.getWrappedObject() : null;
    }

    /**
     * Data-model path of {@code target}, found by identity, or {@code null}. Only values that
     * have an identity worth matching (maps, collections, arrays and beans) are searched for;
     * strings, numbers, dates and the like can be shared between unrelated entries.
     */
    static String find(Map<?, ?> dataModel, Object target) {
        if (!hasIdentity(target)) {
            return null;
        }
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Node> queue = new ArrayDeque<>();
        dataModel.forEach((key, value) -> {
            if (!(value instanceof TemplateModel)) {
                // TemplateModel entries are the helpers, not data
                queue.add(new Node(rootName(key), value, 1));
            }
        });
        int nodes = 0;
        while (!queue.isEmpty() && nodes++ < MAX_NODES) {
            Node node = queue.poll();
            if (node.value() == target) {
                return node.path();
            }
            if (node.depth() < MAX_DEPTH && hasIdentity(node.value()) && visited.add(node.value())) {
                addChildren(queue, node);
            }
        }
        return null;
    }

    private static String rootName(Object key) {
        String name = String.valueOf(key);
        return IDENTIFIER.matcher(name).matches() ? name : "[\"" + name.replace("\"", "\\\"") + "\"]";
    }

    private static void addChildren(Deque<Node> queue, Node node) {
        if (node.value() instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                StringBuilder path = new StringBuilder(node.path());
                appendStep(path, String.valueOf(key));
                queue.add(new Node(path.toString(), value, node.depth() + 1));
            });
        } else if (node.value() instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                queue.add(new Node(node.path() + "[" + i + "]", list.get(i), node.depth() + 1));
            }
        } else if (node.value() instanceof Object[] array) {
            for (int i = 0; i < array.length; i++) {
                queue.add(new Node(node.path() + "[" + i + "]", array[i], node.depth() + 1));
            }
        }
    }

    private static boolean hasIdentity(Object value) {
        return value != null
                && !(value instanceof CharSequence)
                && !(value instanceof Number)
                && !(value instanceof Boolean)
                && !(value instanceof Character)
                && !(value instanceof Enum<?>)
                && !(value instanceof TemporalAccessor)
                && !(value instanceof Date);
    }

    private record Node(String path, Object value, int depth) {
    }
}
