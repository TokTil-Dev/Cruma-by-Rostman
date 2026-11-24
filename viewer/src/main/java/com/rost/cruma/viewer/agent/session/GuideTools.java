package com.rost.cruma.viewer.agent.session;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.AgentHub;
import com.rost.cruma.viewer.agent.Job;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolGroup;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;
import com.rost.cruma.viewer.guide.GuideLibrary;
import com.rost.cruma.viewer.guide.GuidePlainText;
import com.rost.cruma.viewer.guide.GuideSearch;

public final class GuideTools implements ToolGroup {
    private static final int DEFAULT_LIMIT = 10;

    static ToolResult answer(ToolArgs args, GuideLibrary library) throws ToolProblem {
        if (library.getProblem() != null) {
            throw new ToolProblem("The Guide could not be read: " + library.getProblem());
        }
        var action = args.getRequired("action");
        return switch (action) {
            case "find" ->
                find(
                        library,
                        args.getRequired("query"),
                        Math.clamp(args.getInteger("limit", DEFAULT_LIMIT), 1, GuideSearch.MOST_RESULTS));
            case "read" -> read(library, args.getRequired("id"));
            default -> throw new ToolProblem("action is find or read, not \"" + action + "\".");
        };
    }

    @Override
    public void register(Tools tools) {
        tools.hub(
                ToolSpec.named(
                                "guide",
                                "Guide",
                                "Finds and reads the Guide, which explains what each tool and word of Cruma is for, in"
                                    + " the words the person sees. Use it to explain a concept or to name a control.")
                        .choice(
                                "action",
                                "find lists articles that hold every word of query. read gives one article as text.",
                                "find",
                                "read")
                        .text("query", "Words to find, such as tile or export Blender. For find.")
                        .text(
                                "id",
                                "The article to read, such as start/what-is-cruma or terms/tile, as find answers it."
                                        + " For read.")
                        .integer("limit", "How many articles find lists. " + DEFAULT_LIMIT + " when left out.")
                        .required("action")
                        .build(),
                GuideTools::guide);
    }

    private static ToolResult guide(ToolArgs args, AgentHub hub, Job job) throws ToolProblem {
        return answer(args, GuideLibrary.shared());
    }

    private static ToolResult find(GuideLibrary library, String query, int limit) {
        var hits = GuideSearch.search(library, query);
        var articles = new JSONArray();
        for (var hit : hits.subList(0, Math.min(limit, hits.size()))) {
            var article = hit.article();
            var row = new JSONObject();
            row.put("id", article.id());
            row.put("title", article.title());
            row.put("kind", article.kind().getLabel());
            row.put("summary", article.summary());
            articles.add(row);
        }
        var answer = new JSONObject();
        answer.put("articles", articles);
        if (hits.size() > limit) {
            answer.put("more", hits.size() - limit);
        }
        if (hits.isEmpty()) {
            answer.put(
                    "next",
                    "Nothing holds every word. Try fewer words, or a word of the screen, such as tile, export or"
                            + " geodata.");
        } else {
            answer.put("next", "Call guide with action read and an id to read an article.");
        }
        return ToolResult.data(answer);
    }

    private static ToolResult read(GuideLibrary library, String id) throws ToolProblem {
        var article = library.getArticle(id.strip());
        if (article == null) {
            throw new ToolProblem(
                    "The Guide has no article " + id + ". Call guide with action find and a few words to get its id.");
        }
        return ToolResult.text(GuidePlainText.of(article, library));
    }
}
