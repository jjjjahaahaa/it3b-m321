package ch.benedict.m321.batchwriter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.benedict.m321.batchwriter.support.RepositoryFiles;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueft die Regeln aus CLAUDE.md am Quelltext des batch-writer selbst (Szenario S8): ueber jeder
 * Klasse und jeder Methode steht ein Kommentar, und es kommen keine Streams vor. Der Test liest die
 * .java-Dateien als Text. Er ist absichtlich einfach gehalten und geht Zeile fuer Zeile vor,
 * statt den Code wirklich zu parsen. Das reicht fuer den Stil dieses Projekts.
 */
class CommentRulesTest {

    /** Eine Zeile, die eine Klasse, ein Interface, ein Enum oder einen Record beginnt. */
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "^(?:(?:public|protected|private|abstract|final|static|sealed)\\s+)*(?:class|record|interface|enum)\\s+\\w+");

    /** Eine Zeile, die eine Methode beginnt: Modifikatoren, Rueckgabetyp, Name, Klammer auf. */
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "^(?:(?:public|protected|private|static|final|abstract|synchronized|default)\\s+)*"
          + "(?:<[^>]+>\\s+)?[\\w.<>\\[\\],?]+\\s+\\w+\\s*\\(");

    /** Eine Zeile, die einen Konstruktor beginnt: optionaler Modifikator, dann ein Grossbuchstabe und die Klammer. */
    private static final Pattern CONSTRUCTOR_DECLARATION = Pattern.compile(
            "^(?:(?:public|protected|private)\\s+)?[A-Z]\\w*\\s*\\(");

    /**
     * Die verbotenen Schreibweisen fuer Streams. Sie stehen absichtlich in Stuecken, damit dieser Test
     * sich nicht selbst findet, wenn jemand den Quelltext mit einer einfachen Textsuche durchgeht.
     */
    private static final Pattern STREAM_USAGE = Pattern.compile(
            "\\.str" + "eam\\(|\\bStr" + "eam<|\\bStr" + "eam\\.|\\bIntStr" + "eam|\\bCollec" + "tors\\.");

    /** Alle .java-Dateien unter batch-writer/src, Hauptcode und Tests. */
    private List<File> findJavaFiles() {
        File root = RepositoryFiles.find("batch-writer/src").toFile();
        List<File> found = new ArrayList<>();
        collectJavaFiles(root, found);
        return found;
    }

    /** Geht ein Verzeichnis rekursiv durch und sammelt alle .java-Dateien. */
    private void collectJavaFiles(File directory, List<File> found) {
        File[] children = directory.listFiles();
        for (File child : children) {
            if (child.isDirectory()) {
                collectJavaFiles(child, found);
            } else if (child.getName().endsWith(".java")) {
                found.add(child);
            }
        }
    }

    /**
     * Ueber jeder Klasse und jeder Methode (auch Konstruktoren und private Methoden) muss unmittelbar
     * ein Kommentar stehen. Annotationen wie @Test dazwischen sind erlaubt. Der Test nennt jede
     * Verletzung mit Datei und Zeile.
     */
    @Test
    void everyClassAndMethodHasACommentDirectlyAbove() throws IOException {
        List<String> violations = new ArrayList<>();
        for (File file : findJavaFiles()) {
            violations.addAll(findMissingComments(file));
        }

        assertThat(violations).as("Klassen und Methoden ohne Kommentar").isEmpty();
    }

    /** Es darf keine Stream-Verarbeitung vorkommen: for-Schleifen statt Pipelines (CLAUDE.md). */
    @Test
    void noStreamsAreUsed() throws IOException {
        List<String> violations = new ArrayList<>();
        for (File file : findJavaFiles()) {
            violations.addAll(findStreamUsage(file));
        }

        assertThat(violations).as("Stellen mit Streams").isEmpty();
    }

    /** Prueft eine Datei und gibt jede Klasse oder Methode zurueck, ueber der kein Kommentar steht. */
    private List<String> findMissingComments(File file) throws IOException {
        List<String> lines = Files.readAllLines(file.toPath());
        boolean[] isAnnotationLine = markAnnotationLines(lines);
        Deque<String> bodies = new ArrayDeque<>();
        String pendingKind = null;
        boolean insideBlockComment = false;
        List<String> violations = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            String rawLine = lines.get(index).trim();

            // Innerhalb eines /* ... */-Kommentars gibt es nichts zu pruefen.
            if (insideBlockComment) {
                insideBlockComment = !rawLine.contains("*/");
                continue;
            }
            if (rawLine.startsWith("/*")) {
                insideBlockComment = !rawLine.contains("*/");
                continue;
            }
            if (isAnnotationLine[index]) {
                continue;
            }

            String code = stripStringsAndLineComment(rawLine);
            boolean atMemberLevel = bodies.isEmpty() || "type".equals(bodies.peek());
            String declaredKind = null;
            if (atMemberLevel) {
                declaredKind = declarationKind(code, bodies.isEmpty());
            }
            if (declaredKind != null) {
                pendingKind = declaredKind;
                if (!hasCommentAbove(lines, isAnnotationLine, index)) {
                    violations.add(file.getName() + ":" + (index + 1) + "  " + rawLine);
                }
            }
            pendingKind = updateBodies(code, bodies, pendingKind);
        }
        return violations;
    }

    /**
     * Sagt, ob die Zeile eine Klasse ("type") oder eine Methode bzw. einen Konstruktor ("method")
     * beginnt. Liefert null, wenn sie keines von beiden ist. Zuweisungen mit "=" vor der Klammer sind
     * Felder oder Aufrufe und zaehlen nicht.
     */
    private String declarationKind(String code, boolean atFileLevel) {
        if (TYPE_DECLARATION.matcher(code).find()) {
            return "type";
        }
        if (atFileLevel) {
            return null;
        }
        int equalsPosition = code.indexOf('=');
        int parenthesisPosition = code.indexOf('(');
        boolean assignmentBeforeParenthesis = equalsPosition >= 0 && equalsPosition < parenthesisPosition;
        if (parenthesisPosition < 0 || assignmentBeforeParenthesis) {
            return null;
        }
        Matcher method = METHOD_DECLARATION.matcher(code);
        Matcher constructor = CONSTRUCTOR_DECLARATION.matcher(code);
        if (method.find() || constructor.find()) {
            return "method";
        }
        return null;
    }

    /**
     * Fuehrt Buch darueber, in welcher Art von Block der Leser gerade steht ("type", "method" oder
     * "other"). Bei jeder oeffnenden Klammer kommt ein Eintrag dazu, bei jeder schliessenden einer weg.
     * Nur direkt in einem "type"-Block zaehlen Zeilen als Deklarationen. Was in Methoden steht,
     * ist keine. Gibt die noch nicht verbrauchte Art zurueck: hat eine Deklaration ihre Klammer erst
     * in einer spaeteren Zeile, gilt sie bis dahin weiter.
     */
    private String updateBodies(String code, Deque<String> bodies, String pendingKind) {
        String pending = pendingKind;
        for (char character : code.toCharArray()) {
            if (character == '{') {
                if (pending != null) {
                    bodies.push(pending);
                } else {
                    bodies.push("other");
                }
                pending = null;
            } else if (character == '}' && !bodies.isEmpty()) {
                bodies.pop();
            }
        }
        // Eine Deklaration, die mit Semikolon endet, hat keinen Rumpf (zum Beispiel in einem Interface).
        if (pending != null && code.endsWith(";")) {
            pending = null;
        }
        return pending;
    }

    /**
     * Sagt, ob direkt ueber der Zeile ein Kommentar endet. Annotationen (@Test, @Bean, auch mehrzeilige)
     * werden dabei uebersprungen, denn der Kommentar steht ueber ihnen.
     */
    private boolean hasCommentAbove(List<String> lines, boolean[] isAnnotationLine, int declarationIndex) {
        int index = declarationIndex - 1;
        while (index >= 0 && isAnnotationLine[index]) {
            index--;
        }
        if (index < 0) {
            return false;
        }
        String above = lines.get(index).trim();
        return above.endsWith("*/") || above.startsWith("//");
    }

    /**
     * Markiert alle Zeilen, die zu einer eigenstaendigen Annotation gehoeren (eine Zeile wie "@Test"
     * oder eine mehrzeilige wie "@RabbitListener(... )"). Annotationen mitten in einer Parameterliste
     * ("@Value(...) int zahl,") gehoeren nicht dazu, dort steht noch mehr in derselben Zeile.
     */
    private boolean[] markAnnotationLines(List<String> lines) {
        boolean[] marked = new boolean[lines.size()];
        int index = 0;
        while (index < lines.size()) {
            String code = stripStringsAndLineComment(lines.get(index).trim());
            if (!code.startsWith("@") || code.startsWith("@interface")) {
                index++;
                continue;
            }
            int balance = countChar(code, '(') - countChar(code, ')');
            boolean standalone = balance > 0 || !code.contains("(") || code.endsWith(")");
            if (!standalone) {
                index++;
                continue;
            }
            marked[index] = true;
            while (balance > 0 && index + 1 < lines.size()) {
                index++;
                String continuation = stripStringsAndLineComment(lines.get(index).trim());
                marked[index] = true;
                balance = balance + countChar(continuation, '(') - countChar(continuation, ')');
            }
            index++;
        }
        return marked;
    }

    /** Zaehlt, wie oft ein Zeichen in einem Text vorkommt. */
    private int countChar(String text, char wanted) {
        int count = 0;
        for (char character : text.toCharArray()) {
            if (character == wanted) {
                count++;
            }
        }
        return count;
    }

    /**
     * Entfernt Text in Anfuehrungszeichen und einen Zeilenkommentar am Ende. Was uebrig bleibt, ist
     * nur noch Code. So zaehlen Klammern in Texten und Kommentaren nicht mit.
     */
    private String stripStringsAndLineComment(String line) {
        String withoutStrings = line.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
        withoutStrings = withoutStrings.replaceAll("'(\\\\.|[^'\\\\])'", "''");
        int commentStart = withoutStrings.indexOf("//");
        if (commentStart >= 0) {
            return withoutStrings.substring(0, commentStart).trim();
        }
        return withoutStrings.trim();
    }

    /** Sucht in einer Datei jede Zeile, die Stream-Verarbeitung enthaelt. Kommentare und Texte zaehlen nicht. */
    private List<String> findStreamUsage(File file) throws IOException {
        List<String> lines = Files.readAllLines(file.toPath());
        List<String> violations = new ArrayList<>();
        boolean insideBlockComment = false;

        for (int index = 0; index < lines.size(); index++) {
            String rawLine = lines.get(index).trim();
            if (insideBlockComment) {
                insideBlockComment = !rawLine.contains("*/");
                continue;
            }
            if (rawLine.startsWith("/*")) {
                insideBlockComment = !rawLine.contains("*/");
                continue;
            }
            String code = stripStringsAndLineComment(rawLine);
            if (STREAM_USAGE.matcher(code).find()) {
                violations.add(file.getName() + ":" + (index + 1) + "  " + rawLine);
            }
        }
        return violations;
    }
}
