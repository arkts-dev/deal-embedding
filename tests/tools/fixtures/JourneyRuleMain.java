package dev.deal.cutover.tests;

/**
 * Runs the journey time-evidence rules on the JVM: they are pure over the rendered tree, so the same
 * assertions the device lane executes inside `CutoverInstrumentation` are also a deterministic item.
 * Everything else in the instrumentation needs an installed device, so only this entry point runs here.
 */
public final class JourneyRuleMain {
    private JourneyRuleMain() {}

    public static void main(String[] arguments) {
        JourneyObservationTestKt.checkJourneyObservation();
        System.out.println("journey evidence rules: the window, rollover, arrival and required-item rules that "
                + "the acceptance journeys read a rendered tree with accept their declared spells and refuse the "
                + "shapes that must never prove them");
    }
}
