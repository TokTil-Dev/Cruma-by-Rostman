package com.rost.cruma.viewer.agent.film;

import com.rost.cruma.core.world.GameScenes.Model;
import com.rost.cruma.core.world.SceneOrders.Skills;
import com.rost.cruma.core.world.SceneOrders.Speeds;
import com.rost.cruma.core.world.SoundSpans.Kind;
import com.rost.cruma.viewer.agent.ToolProblem;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

interface FilmLookup {
    record Picture(String name, int width, int height) {}

    String getClient();

    int findNpc(String text, List<String> notes) throws ToolProblem;

    @Nullable
    String getNpcName(int npc);

    int getHelper();

    int findSkill(String text, List<String> notes) throws ToolProblem;

    @Nullable
    String getSkillName(int skill);

    Skills getSkills();

    int findItem(String text) throws ToolProblem;

    @Nullable
    String findMissingFigure(String figure);

    @Nullable
    Map<String, Double> getSequences(int npc);

    Speeds getSpeeds();

    @Nullable
    Model getModel();

    List<String> getClips(Kind kind);

    List<Picture> getSlides();

    List<Integer> getPostEffects();

    List<String> getEffects();
}
