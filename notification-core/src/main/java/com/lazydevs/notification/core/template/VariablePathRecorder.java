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

    /** A root variable or a {@code .name} step. */
    private static final Pattern NAME = Pattern.compile("[\\p{L}_$@][\\p{L}\\p{N}_$@]*");
    /** Longest {@code [n]} index accepted, so it always fits an {@code int}. */
    private static final int MAX_INDEX_DIGITS = 9;
    /** {@link #parseStep} result when no step starts at the position. */
    private static final int NO_STEP = -1;
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
        return env.getCustomAttribute(ATTRIBUTE) instanceof Failure(TemplateException failed, String path)
                && failed == exception ? path : null;
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
     * empty list when the expression is not a plain variable path. A hand-written scanner
     * rather than one regular expression, so its cost and stack use stay linear in the length
     * of the expression.
     */
    static List<Object> parse(String expression) {
        if (expression == null) {
            return List.of();
        }
        String text = expression.strip();
        Matcher name = NAME.matcher(text);
        if (!name.lookingAt()) {
            return List.of();
        }
        List<Object> steps = new ArrayList<>();
        steps.add(name.group());
        int position = name.end();
        while (position < text.length()) {
            position = parseStep(text, position, name, steps);
            if (position == NO_STEP) {
                return List.of();
            }
        }
        return steps;
    }

    /**
     * Adds the step at {@code position} ({@code .name}, {@code [0]}, {@code ["key"]} or
     * {@code ['key']}) to {@code steps}; returns the position after it, or {@link #NO_STEP}.
     */
    private static int parseStep(String text, int position, Matcher name, List<Object> steps) {
        char first = text.charAt(position);
        if (first == '.') {
            name.region(position + 1, text.length());
            if (!name.lookingAt()) {
                return NO_STEP;
            }
            steps.add(name.group());
            return name.end();
        }
        if (first != '[' || position + 1 >= text.length()) {
            return NO_STEP;
        }
        char open = text.charAt(position + 1);
        return open == '"' || open == '\'' ? quotedStep(text, position + 2, open, steps)
                : indexStep(text, position + 1, steps);
    }

    /** {@code [n]} with one to {@link #MAX_INDEX_DIGITS} ASCII digits, starting after the {@code [}. */
    private static int indexStep(String text, int start, List<Object> steps) {
        int end = start;
        while (end < text.length() && end - start < MAX_INDEX_DIGITS && isAsciiDigit(text.charAt(end))) {
            end++;
        }
        if (end == start || end >= text.length() || text.charAt(end) != ']') {
            return NO_STEP;
        }
        steps.add(Integer.valueOf(text.substring(start, end)));
        return end + 1;
    }

    /**
     * {@code "key"]} or {@code 'key']}, starting after the opening quote. A backslash escapes
     * the next character, which must not be a line terminator.
     */
    private static int quotedStep(String text, int start, char quote, List<Object> steps) {
        StringBuilder key = new StringBuilder();
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i++);
            if (c == quote) {
                if (i >= text.length() || text.charAt(i) != ']') {
                    return NO_STEP;
                }
                steps.add(key.toString());
                return i + 1;
            }
            if (c == '\\') {
                if (i >= text.length() || isLineTerminator(text.charAt(i))) {
                    return NO_STEP;
                }
                c = text.charAt(i++);
            }
            key.append(c);
        }
        return NO_STEP;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** The characters {@code .} does not match in a regular expression without DOTALL. */
    private static boolean isLineTerminator(char c) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
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
