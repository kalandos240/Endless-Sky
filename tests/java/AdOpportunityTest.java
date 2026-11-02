package io.github.kalandos240.endlesssky;

public final class AdOpportunityTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        AdOpportunity opportunity = new AdOpportunity();
        check(opportunity.consume(0, 0) == 0, "flight is not an opportunity");
        check(opportunity.consume(5, 0) == 0, "menu must settle");
        check(opportunity.consume(5, 749) == 0, "too early");
        check(opportunity.consume(5, 750) == 5, "settled menu entry");
        check(opportunity.consume(5, 150_750) == 0, "no late ad in the same menu");
        opportunity.clear();
        check(opportunity.consume(5, 151_000) == 0, "resume must settle");
        check(opportunity.consume(5, 152_000) == 0, "resume cannot repeat an entry");
        check(opportunity.consume(10, 153_000) == 0, "direct planet transition");
        check(opportunity.consume(10, 153_750) == 10, "planet opportunity");
        check(opportunity.consume(0, 154_000) == 0, "dialog/takeoff removes opportunity");
        check(opportunity.consume(14, 154_100) == 0, "new landing candidate");
        check(opportunity.consume(0, 154_200) == 0, "mission dialog cancels candidate");
        check(opportunity.consume(18, 154_300) == 0, "return from mission dialog");
        check(opportunity.consume(18, 155_050) == 18, "settled return opportunity");
        check(opportunity.consume(22, 155_100) == 0, "new scene");
        opportunity.clear();
        check(opportunity.consume(22, 200_000) == 0, "background must restart settling");
        check(opportunity.consume(22, 200_750) == 22, "resume an unconsumed scene");
        System.out.println("Ad opportunities: menu, landing, transient dialogs, background and deduplication passed");
    }
}
