package com.rost.cruma.viewer.guide;

import com.rost.cruma.viewer.guide.GuideArticle.Kind;
import com.rost.cruma.viewer.guide.GuideMarkup.Problem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class GuideLibrary {
    public static final String TERMS = "terms";

    public static final String ENGLISH = "en";

    private static final String ROOT = "guide/";
    private static final String TITLE = "title: ";

    private final List<Section> sections;
    private final Section terms;
    private final Map<String, GuideArticle> byId;
    private final @Nullable String problem;

    private GuideLibrary(List<Section> sections, Section terms, @Nullable String problem) {
        this.sections = List.copyOf(sections);
        this.terms = terms;
        this.problem = problem;
        var all = new LinkedHashMap<String, GuideArticle>();
        for (var section : sections) {
            for (var article : section.articles()) {
                all.put(article.id(), article);
            }
        }
        for (var term : terms.articles()) {
            all.put(term.id(), term);
        }
        byId = all;
    }

    @FunctionalInterface
    public interface Source {
        @Nullable
        String read(String path) throws IOException;
    }

    public record Section(String id, String title, List<GuideArticle> articles) {
        public Section {
            articles = List.copyOf(articles);
        }
    }

    public static GuideLibrary shared() {
        return Shared.LIBRARY;
    }

    public static void warmUp() {
        CompletableFuture.runAsync(GuideLibrary::shared);
    }

    public static Source resources() {
        return path -> {
            try (var stream = GuideLibrary.class.getClassLoader().getResourceAsStream(path)) {
                return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        };
    }

    public static GuideLibrary load(String language, Source source) throws IOException, Problem {
        var list = source.read(ROOT + "sections.txt");
        if (list == null) {
            throw new Problem(ROOT + "sections.txt", 0, "the list of sections is missing");
        }
        var sections = new ArrayList<Section>();
        for (var id : lines(list)) {
            if (id.equals(TERMS)) {
                throw new Problem(ROOT + "sections.txt", 0, "the glossary is not a section of Home: leave terms out");
            }
            sections.add(findSection(id, language, source));
        }
        return new GuideLibrary(sections, findSection(TERMS, language, source), null);
    }

    public static GuideLibrary broken(String problem) {
        return new GuideLibrary(List.of(), new Section(TERMS, "Glossary", List.of()), problem);
    }

    public static GuideLibrary read(String language, Source source) {
        try {
            return load(language, source);
        } catch (IOException | Problem | RuntimeException failure) {
            LogManager.getLogger(GuideLibrary.class).warn("Could not read the Guide", failure);
            var message = failure.getMessage();
            return broken(
                    message == null || message.isBlank() ? failure.getClass().getSimpleName() : message);
        }
    }

    private static final class Shared {
        private static final GuideLibrary LIBRARY = read(ENGLISH, resources());
    }

    public List<Section> getSections() {
        return sections;
    }

    public @Nullable Section findSection(String id) {
        if (id.equals(TERMS)) {
            return terms;
        }
        for (var section : sections) {
            if (section.id().equals(id)) {
                return section;
            }
        }
        return null;
    }

    public List<GuideArticle> getTerms() {
        return terms.articles();
    }

    public List<GuideArticle> getAll() {
        return List.copyOf(byId.values());
    }

    public @Nullable GuideArticle getArticle(String id) {
        return byId.get(id);
    }

    public List<GuideArticle> forTool(String tool) {
        var found = new ArrayList<GuideArticle>();
        for (var section : sections) {
            for (var article : section.articles()) {
                if (tool.equals(article.tool())) {
                    found.add(article);
                }
            }
        }
        return found;
    }

    public @Nullable String getProblem() {
        return problem;
    }

    private static Section findSection(String id, String language, Source source) throws IOException, Problem {
        var indexPath = file(language, id + "/index.txt", source);
        var index = source.read(indexPath);
        if (index == null) {
            throw new Problem(ROOT + ENGLISH + "/" + id + "/index.txt", 0, "the section " + id + " has no index");
        }
        var lines = lines(index);
        if (lines.isEmpty() || !lines.getFirst().startsWith(TITLE)) {
            throw new Problem(indexPath, 1, "an index starts with the section's title: \"title: Geodata\"");
        }
        var title = lines.getFirst().substring(TITLE.length()).strip();
        var articles = new ArrayList<GuideArticle>();
        for (var slug : lines.subList(1, lines.size())) {
            var path = file(language, id + "/" + slug + ".md", source);
            var text = source.read(path);
            if (text == null) {
                throw new Problem(indexPath, 0, "the index names " + slug + ", which has no file");
            }
            var article = GuideMarkup.parse(id + "/" + slug, path, text);
            if (id.equals(TERMS) != (article.kind() == Kind.TERM)) {
                throw new Problem(path, 0, id.equals(TERMS) ? "a term has kind: term" : "only a term has kind: term");
            }
            articles.add(article);
        }
        return new Section(id, title, articles);
    }

    private static String file(String language, String name, Source source) throws IOException {
        var own = ROOT + language + "/" + name;
        if (!language.equals(ENGLISH) && source.read(own) != null) {
            return own;
        }
        return ROOT + ENGLISH + "/" + name;
    }

    private static List<String> lines(String text) {
        var lines = new ArrayList<String>();
        for (var line : text.split("\n")) {
            var stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                lines.add(stripped);
            }
        }
        return lines;
    }
}
