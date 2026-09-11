# M321 — Lernprojekt IT3b

Unterrichtsprojekt der Klasse IT3b. Der Code wird von Lernenden gelesen, verstanden und **mündlich erklärt**.
Massstab ist nicht Eleganz, sondern: kann eine lernende Person jede Zeile vorlesen und sagen, was sie tut?

## Codestil

- **Eine Anweisung pro Zeile.** Keine verschachtelten One-Liner, keine Ketten aus Stream/Optional/
  Ternary. Zwischenresultate in benannte Variablen legen, auch wenn sie nur einmal gebraucht werden.
- **Keine Sondermodelle.** Kein Reflection, keine generischen Basisklassen, keine Annotation-Magie,
  keine Design Patterns, die im Unterricht nicht behandelt wurden. Wenn eine Lösung eine Erklärung
  braucht, die über den Stoff hinausgeht, ist es die falsche Lösung.
- **Standardbibliothek und einfache Konstrukte.** `for`-Schleife statt Stream-Pipeline, `if` statt
  Ternary-Verschachtelung, `ArrayList`/`HashMap` statt exotischer Collections.
- **Sprechende Namen** in ganzen Wörtern (`empfangeneNachricht`, nicht `msg2`).
- **Kurze Methoden** mit genau einer Aufgabe. Lieber drei benannte Methoden als eine kluge.

## Kommentare

- Über jeder Methode ein bis zwei Sätze: **was** sie tut und **warum** es sie gibt.
- Innerhalb einer Methode jeder nicht offensichtliche Schritt kommentiert — besonders alles, was mit
  Netzwerk, Threads, Sockets oder Nebenläufigkeit zu tun hat.
- Kommentare auf Deutsch, im Ton einer Erklärung an eine Mitlernende, nicht als Stichwort.
- Kein Kommentar, der nur den Code wiederholt (`// i erhöhen`). Kommentiert wird die Absicht.

## Übungsaufgaben

Vereinzelt — nicht bei jedem Auftrag — eine **kleine Codingaufgabe** an die Lernenden vergeben statt sie
selbst zu lösen. Regeln dafür:

- Umfang: **2–3 Zeilen** einfacher Code an einer klar bezeichneten Stelle.
- Die Stelle im Code mit `// TODO Übung: <Aufgabe in einem Satz>` markieren.
- In der Antwort kurz sagen, was dort passieren soll und welchen Baustein es dafür braucht —
  **ohne die Lösung hinzuschreiben**.
- Der Rest der Datei muss ohne diese Zeilen kompilieren oder mit einem klaren Hinweis fehlschlagen,
  nie stillschweigend falsch laufen.
- Wird die Lösung eingereicht: durchgehen, benennen was gut ist, höchstens einen Punkt verbessern.

## Antworten

Erklären statt abliefern. Zu jeder Änderung in wenigen Sätzen: was wurde gemacht, warum so, und
welcher Teil davon Prüfungsstoff ist.

## Git und GitHub

Dieses Repository ist ein **Fork**. Gearbeitet wird ausschliesslich im eigenen Fork
(`origin`), niemals im Original des Lehrers (`upstream`).

- **Nie einen Pull Request auf `upstream` öffnen.** Weder über die Weboberfläche noch mit
  `gh pr create`. Auch nicht vorschlagen. Pull Requests gehen immer nur von einem Branch
  des eigenen Forks auf `main` des eigenen Forks.
- Bei `gh pr create` muss `--repo` auf den eigenen Fork zeigen. Ohne diese Angabe wählt das
  Werkzeug bei einem Fork von sich aus das Original als Ziel.
- Nach `upstream` wird **nicht** gepusht. Die Push-Adresse ist dafür bewusst gesperrt.
- Von `upstream` darf gelesen werden (`git fetch upstream`), um Änderungen des Lehrers
  nachzuziehen.
