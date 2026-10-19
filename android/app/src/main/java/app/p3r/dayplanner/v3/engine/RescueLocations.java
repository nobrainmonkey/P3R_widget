package app.p3r.dayplanner.v3.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import app.p3r.dayplanner.v3.model.Rules;

/** Shared floor lists for a single rescue window or all people on a planned tower visit. */
public final class RescueLocations {
    private RescueLocations() {}

    private static List<Rules.RescuePerson> sorted(Collection<Rules.RescueGroup> groups) {
        List<Rules.RescuePerson> people = new ArrayList<>();
        for (Rules.RescueGroup group : groups) people.addAll(group.people);
        people.sort(Comparator.comparingInt((Rules.RescuePerson person) -> person.floor)
                .thenComparingInt(person -> person.id));
        return people;
    }

    /** Example: 50F、56F、64F. */
    public static String floors(Collection<Rules.RescueGroup> groups) {
        List<String> floors = new ArrayList<>();
        for (Rules.RescuePerson person : sorted(groups)) floors.add(person.floor + "F");
        return String.join("、", floors);
    }

    /** One person per line in the order the player reaches them. */
    public static String details(Collection<Rules.RescueGroup> groups) {
        List<String> lines = new ArrayList<>();
        for (Rules.RescuePerson person : sorted(groups)) {
            lines.add(person.floor + "F · " + person.displayName());
        }
        return String.join("\n", lines);
    }
}
