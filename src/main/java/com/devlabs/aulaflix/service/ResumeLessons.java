package com.devlabs.aulaflix.service;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

/**
 * The Resume lesson, where "Continuar" takes the Student. Nothing stores it: it follows from the outline's current
 * order, the Student's marks and the last Lesson they opened, so reordering the outline moves it along.
 */
final class ResumeLessons {

    private ResumeLessons() {
    }

    /**
     * The index in the outline of the Resume lesson, the first of these that applies:
     * <ol>
     *     <li>the last Lesson opened, if not completed;</li>
     *     <li>the next unfinished published Lesson after it;</li>
     *     <li>the first unfinished published Lesson, which is also the rule with no visit yet;</li>
     *     <li>with every Lesson done, the first published Lesson.</li>
     * </ol>
     * A Course with no published Lesson has none. A last visit to a Lesson no longer in the outline counts as none.
     *
     * @param outline       every Lesson of the Course, "Em breve" ones included, in the outline's order
     * @param completedIds  the ids of the Lessons the Student completed
     * @param lastVisitedId the id of the last Lesson the Student opened in the Course, or null before any visit
     */
    static OptionalInt indexIn(List<OutlineLesson> outline, Set<Long> completedIds, Long lastVisitedId) {
        IntPredicate unfinished = index -> outline.get(index).published()
                && !completedIds.contains(outline.get(index).id());
        OptionalInt visited = lastVisitedId == null ? OptionalInt.empty() : IntStream.range(0, outline.size())
                .filter(index -> outline.get(index).id() == lastVisitedId)
                .findFirst();
        if (visited.isPresent()) {
            OptionalInt fromVisited = IntStream.range(visited.getAsInt(), outline.size()).filter(unfinished)
                    .findFirst();
            if (fromVisited.isPresent()) {
                return fromVisited;
            }
        }
        OptionalInt firstUnfinished = IntStream.range(0, outline.size()).filter(unfinished).findFirst();
        if (firstUnfinished.isPresent()) {
            return firstUnfinished;
        }
        return IntStream.range(0, outline.size()).filter(index -> outline.get(index).published()).findFirst();
    }

    /** A Lesson of the outline, as the rules see it. */
    record OutlineLesson(long id, boolean published) {
    }
}
