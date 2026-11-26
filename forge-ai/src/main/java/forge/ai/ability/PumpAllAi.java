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

        // a -N/-N or -X/-X to all creatures is harmful whether or not its script says IsCurse (Toxic Deluge, Cloudkill, Terror
        // Tide do not): without it the AI weighed it as a pump of its own creatures and never cast it as a sweeper
        final boolean curse = sa.isCurse() || sa.getParamOrDefault("NumDef", "").trim().startsWith("-");

        // -X/-X for X life (Toxic Deluge): the script gives the AI no X, so it never cast it; pick the X that pays off best
        if (curse && "-X".equals(sa.getParam("NumDef")) && "Count$xPaid".equals(sa.getSVar("X"))
                && abCost != null && abCost.hasSpecificCostType(forge.game.cost.CostPayLife.class)) {
            final Integer x = lifeSweeperX(ai, sa, source, sa.getParamOrDefault("ValidCards", ""));
            if (x == null) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
            sa.setXManaCostPaid(x);
        }

        final int power = AbilityUtils.calculateAmount(source, sa.getParam("NumAtt"), sa);
        final int defense = AbilityUtils.calculateAmount(source, sa.getParam("NumDef"), sa);
        final List<String> keywords = sa.hasParam("KW") ? Arrays.asList(sa.getParam("KW").split(" & ")) : new ArrayList<>();
        final PhaseType phase = game.getPhaseHandler().getPhase();

        final String valid = sa.getParamOrDefault("ValidCards", "");

        CardCollection comp = CardLists.getValidCards(ai.getCardsIn(ZoneType.Battlefield), valid, source.getController(), source, sa);
        // a -X/-X to every creature hits every opponent's board, not only the strongest opponent's
        final boolean everyone = curse && ai.getOpponents().size() > 1;
        CardCollection human = CardLists.getValidCards((everyone ? ai.getOpponents().getCardsIn(ZoneType.Battlefield) : opp.getCardsIn(ZoneType.Battlefield)),
                valid, source.getController(), source, sa);

        if (curse) {
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

            // evaluate both lists and pass only if human creatures are more valuable
            final int ours = ComputerUtilCard.evaluateCreatureList(comp), theirs = ComputerUtilCard.evaluateCreatureList(human);
            boolean result = everyone
                    ? ComputerUtilCard.sweeperPaysOff(theirs, ours, ComputerUtilCard.SWEEP_MARGIN) || (theirs > ours && ComputerUtilCard.wouldDiscardAtCleanup(ai, sa))
                    : ours + 200 < theirs;
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

    /**
     * The X for a "-X/-X to all creatures, pay X life" sweeper: of the toughnesses on the board, the one where the creatures it
     * kills are worth most to the opponents against what it costs us (our creatures, and 15 per life point), keeping the AI at
     * 10 life or half of a lower total. Null when no X pays off by the usual sweeper rule.
     */
    static Integer lifeSweeperX(final Player ai, final SpellAbility sa, final Card source, final String valid) {
        final boolean several = ai.getOpponents().size() > 1;
        final CardCollection theirsAll = CardLists.getValidCards(ai.getOpponents().getCardsIn(ZoneType.Battlefield), valid, source.getController(), source, sa);
        final CardCollection oursAll = CardLists.getValidCards(ai.getCardsIn(ZoneType.Battlefield), valid, source.getController(), source, sa);
        final int most = ai.getLife() - Math.min(10, (ai.getLife() + 1) / 2);   // keep 10 life, or half of a low total (a wipe can be what keeps it alive)
        Integer best = null;
        int bestScore = Integer.MIN_VALUE;
        java.util.TreeSet<Integer> toughness = new java.util.TreeSet<>();
        for (Card c : theirsAll) if (c.isCreature() && c.getNetToughness() > 0) toughness.add(c.getNetToughness());
        for (int x : toughness) {
            if (x > most) break;
            final CardCollection theirs = CardLists.filter(theirsAll, c -> c.getNetToughness() <= x);
            final CardCollection ours = CardLists.filter(oursAll, c -> c.getNetToughness() <= x);
            final int t = ComputerUtilCard.evaluateCreatureList(theirs), o = ComputerUtilCard.evaluateCreatureList(ours);
            final boolean pays = several ? ComputerUtilCard.sweeperPaysOff(t, o, ComputerUtilCard.SWEEP_MARGIN) : o + 200 < t;
            final int score = t - o - 15 * x;
            if (pays && score > bestScore) {
                bestScore = score;
                best = x;
            }
        }
        return best;
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
