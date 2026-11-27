package forge.ai.ability;

import forge.ai.*;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.combat.Combat;
import forge.game.cost.Cost;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PumpAllAi extends PumpAiBase {

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#canPlayAI(forge.game.player.Player, java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    protected AiAbilityDecision checkApiLogic(final Player ai, final SpellAbility sa) {
        final Card source = sa.getHostCard();
        final Game game = ai.getGame();
        final Combat combat = game.getCombat();
        final Cost abCost = sa.getPayCosts();
        final String logic = sa.getParamOrDefault("AILogic", "");

        if (logic.equals("UntapCombatTrick")) {
            PhaseHandler ph = ai.getGame().getPhaseHandler();
            if (!(ph.is(PhaseType.COMBAT_DECLARE_BLOCKERS, ai)
                    || (!ph.isPlayerTurn(ai) && ph.is(PhaseType.COMBAT_DECLARE_ATTACKERS)))) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }

        if (abCost != null && source.hasSVar("AIPreference")) {
            if (!ComputerUtilCost.checkSacrificeCost(ai, abCost, source, sa, true)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }
        
        final Player opp = ai.getStrongestOpponent();

        if (sa.usesTargeting()) {
            if (sa.canTarget(opp) && sa.isCurse()) {
                sa.resetTargets();
                sa.getTargets().add(opp);
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }

            if (sa.canTarget(ai) && !sa.isCurse()) {
                sa.resetTargets();
                sa.getTargets().add(ai);
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
        }

        // "pay X life: all creatures get -X/-X" (Toxic Deluge): pick the X that kills the most of the opponents' creatures
        // against ours, keeping enough life; with no X chosen it read as -0/-0 and was never cast
        if ("-X".equals(sa.getParam("NumDef")) && "Count$xPaid".equals(source.getSVar("X")) && abCost != null
                && abCost.hasSpecificCostType(forge.game.cost.CostPayLife.class) ) {
            final int spare = ai.getLife() - Math.max(5, ai.getStartingLife() / 8);
            int bestX = 0, best = 0;
            for (int x = 1; x <= Math.min(spare, 20); x++) {
                final int k = x;
                int theirs = ComputerUtilCard.evaluateCreatureList(CardLists.filter(
                        CardLists.getValidCards(ai.getOpponents().getCardsIn(ZoneType.Battlefield), sa.getParam("ValidCards"), ai, source, sa),
                        c -> c.getNetToughness() <= k));
                int ours = ComputerUtilCard.evaluateCreatureList(CardLists.filter(
                        CardLists.getValidCards(ai.getCardsIn(ZoneType.Battlefield), sa.getParam("ValidCards"), ai, source, sa),
                        c -> c.getNetToughness() <= k));
                // each point of life paid counts as a little value, so the smallest X that does the job wins
                int gain = theirs - ours - 200 - 10 * x;
                if (gain > best) {
                    best = gain;
                    bestX = x;
                }
            }
            if (bestX == 0) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            sa.setXManaCostPaid(bestX);
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        final int power = AbilityUtils.calculateAmount(source, sa.getParam("NumAtt"), sa);
        final int defense = AbilityUtils.calculateAmount(source, sa.getParam("NumDef"), sa);
        final List<String> keywords = sa.hasParam("KW") ? Arrays.asList(sa.getParam("KW").split(" & ")) : new ArrayList<>();
        final PhaseType phase = game.getPhaseHandler().getPhase();

        final String valid = sa.getParamOrDefault("ValidCards", "");

        CardCollection comp = CardLists.getValidCards(ai.getCardsIn(ZoneType.Battlefield), valid, source.getController(), source, sa);
        // an untargeted effect reaches every opponent (Massacre Wurm, Elesh Norn): weigh all of their creatures, not only the
        // strongest opponent's, or in Commander a -2/-2 that kills half the table reads as killing nothing worth it
        CardCollection human = CardLists.getValidCards(
                (sa.usesTargeting() ? opp.getCardsIn(ZoneType.Battlefield) : ai.getOpponents().getCardsIn(ZoneType.Battlefield)),
                valid, source.getController(), source, sa);

        if (sa.isCurse()) {
            if (defense < 0) { // try to destroy creatures
                comp = CardLists.filter(comp, c -> {
                    if (c.getNetToughness() <= -defense) {
                        return true; // can kill indestructible creatures
                    }
                    return ComputerUtilCombat.getDamageToKill(c, false) <= -defense && !c.hasKeyword(Keyword.INDESTRUCTIBLE);
                }); // leaves all creatures that will be destroyed
                human = CardLists.filter(human, c -> {
                    if (c.getNetToughness() <= -defense) {
                        return true; // can kill indestructible creatures
                    }
                    return ComputerUtilCombat.getDamageToKill(c, false) <= -defense && !c.hasKeyword(Keyword.INDESTRUCTIBLE);
                }); // leaves all creatures that will be destroyed
            } // -X/-X end
            else if (power < 0) { // -X/-0
                if (phase.isAfter(PhaseType.COMBAT_DECLARE_BLOCKERS)
                        || phase.isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)
                        || game.getPhaseHandler().isPlayerTurn(sa.getActivatingPlayer())
                        || game.getReplacementHandler().isPreventCombatDamageThisTurn()) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
                int totalPower = 0;
                for (Card c : human) {
                    if (combat == null || !combat.isAttacking(c)) {
                        continue;
                    }
                    totalPower += Math.min(c.getNetPower(), power * -1);
                    if (phase == PhaseType.COMBAT_DECLARE_BLOCKERS && combat.isUnblocked(c)) {
                        if (ComputerUtilCombat.lifeInDanger(sa.getActivatingPlayer(), combat)) {
                            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                        }
                        totalPower += Math.min(c.getNetPower(), power * -1);
                    }
                    if (totalPower >= power * -2) {
                        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                    }
                }
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            } // -X/-0 end
            
            if (comp.isEmpty() && ComputerUtil.activateForCost(sa, ai)) {
            	return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }

            // evaluate both lists and pass only if human creatures are more valuable; a trigger (Massacre Wurm entering) costs
            // nothing of its own, so it only has to do no more harm to us than to them, or the creature stays in hand
            int margin = sa.isTrigger() ? 0 : 200;
            int ours = ComputerUtilCard.evaluateCreatureList(comp);
            int theirs = ComputerUtilCard.evaluateCreatureList(human);
            boolean result = sa.isTrigger() ? ours <= theirs : ours + margin < theirs;
            return result ? new AiAbilityDecision(100, AiPlayDecision.WillPlay) : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        } // end Curse

        if (!game.getStack().isEmpty()) {
            boolean result = pumpAgainstRemoval(ai, sa, comp);
            return result ? new AiAbilityDecision(100, AiPlayDecision.WillPlay) : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        boolean result = ai.getCreaturesInPlay().anyMatch(c -> c.isValid(valid, source.getController(), source, sa)
                && ComputerUtilCard.shouldPumpCard(ai, sa, c, defense, power, keywords));
        if (!result && sa.isSpell() && sa.getHostCard().hasKeyword(Keyword.CASCADE)
                && (!(ai.getController() instanceof PlayerControllerAi)
                || ((PlayerControllerAi) ai.getController()).getAi().deckPlan(ai).combos.mayCascadeForTheFreeSpell(ai))) {
            // the free spell from cascade is worth the mana even when nothing is there to pump (Violent Outburst, #4210),
            // unless the deck plan keeps its cascade spells for the right moment (Living End)
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return result ? new AiAbilityDecision(100, AiPlayDecision.WillPlay) : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player ai, SpellAbility sa, boolean mandatory) {
        // it might help so take it
        if (!sa.usesTargeting() && !sa.isCurse() && sa.hasParam("ValidCards") && sa.getParam("ValidCards").contains("YouCtrl")) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        // important to call canPlay first so targets are added if needed
        AiAbilityDecision decision = canPlay(ai, sa);
        if (mandatory && !decision.decision().willingToPlay()) {
            return new AiAbilityDecision(50, AiPlayDecision.MandatoryPlay);
        }
        return decision;
    }

    boolean pumpAgainstRemoval(Player ai, SpellAbility sa, List<Card> comp) {
        final List<GameObject> objects = ComputerUtil.predictThreatenedObjects(sa.getActivatingPlayer(), sa, true);
        for (final Card c : comp) {
            if (objects.contains(c)) {
                return true;
            }
        }
        return false;
    }
}
