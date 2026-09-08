package gg.xp.xivsupport.triggers.ultimate;

import gg.xp.reevent.events.Event;
import gg.xp.xivsupport.events.actlines.events.BuffApplied;
import gg.xp.xivsupport.events.state.combatstate.StatusEffectRepository;
import gg.xp.xivsupport.events.triggers.marks.adv.MarkerSign;
import gg.xp.xivsupport.events.triggers.marks.adv.SpecificAutoMarkRequest;
import gg.xp.xivsupport.models.XivPlayerCharacter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class DmuDebuffMarks {

	private record Holder(BuffApplied buff, MarkerSign sign) {}

	private final StatusEffectRepository buffs;
	private final List<List<List<Holder>>> pairs = new ArrayList<>();
	private final Map<XivPlayerCharacter, MarkerSign> marked = new LinkedHashMap<>();

	DmuDebuffMarks(StatusEffectRepository buffs) {
		this.buffs = buffs;
	}

	void start(List<BuffApplied> debuffs, Consumer<Event> emit) {
		clear(emit);
		addPairs(debuffs, 0x15A8, MarkerSign.ATTACK1, MarkerSign.ATTACK2);
		addPairs(debuffs, 0x15A9, MarkerSign.BIND1, MarkerSign.BIND2);
		addPairs(debuffs, 0x15A7, MarkerSign.IGNORE1, MarkerSign.IGNORE2);
		update(emit);
	}

	private void addPairs(List<BuffApplied> debuffs, long id, MarkerSign first, MarkerSign second) {
		Map<XivPlayerCharacter, BuffApplied> unique = new LinkedHashMap<>();
		debuffs.stream().filter(ba -> ba.buffIdMatches(id)).forEach(ba -> {
			if (ba.getTarget() instanceof XivPlayerCharacter player) {
				unique.put(player, ba);
			}
		});
		// Application order can be reversed. Mark the next pair to resolve.
		List<BuffApplied> ordered = unique.values().stream()
				.sorted(Comparator.comparing(ba -> ba.getEffectiveHappenedAt().plus(ba.getInitialDuration())))
				.toList();
		List<List<Holder>> queue = new ArrayList<>();
		for (int i = 0; i < ordered.size(); i += 2) {
			List<Holder> pair = new ArrayList<>();
			pair.add(new Holder(ordered.get(i), first));
			if (i + 1 < ordered.size()) {
				pair.add(new Holder(ordered.get(i + 1), second));
			}
			queue.add(pair);
		}
		pairs.add(queue);
	}

	private boolean active(Holder holder) {
		BuffApplied latest = buffs.getLatest(holder.buff());
		return latest != null && !latest.wouldBeExpired();
	}

	void update(Consumer<Event> emit) {
		if (pairs.isEmpty() && marked.isEmpty()) {
			return;
		}
		List<Holder> current = new ArrayList<>();
		for (List<List<Holder>> queue : pairs) {
			while (!queue.isEmpty() && queue.get(0).stream().noneMatch(this::active)) {
				queue.remove(0);
			}
			if (!queue.isEmpty()) {
				queue.get(0).stream().filter(this::active).forEach(current::add);
			}
		}
		pairs.removeIf(List::isEmpty);
		current.sort(Comparator.comparing(h -> h.buff().getEffectiveHappenedAt().plus(h.buff().getInitialDuration())));
		Map<XivPlayerCharacter, MarkerSign> desired = new LinkedHashMap<>();
		for (Holder holder : current) {
			desired.putIfAbsent((XivPlayerCharacter) holder.buff().getTarget(), holder.sign());
		}
		marked.forEach((player, sign) -> {
			if (!desired.containsKey(player)) {
				emit.accept(new SpecificAutoMarkRequest(player, MarkerSign.CLEAR));
			}
		});
		desired.forEach((player, sign) -> {
			if (marked.get(player) != sign) {
				emit.accept(new SpecificAutoMarkRequest(player, sign));
			}
		});
		marked.clear();
		marked.putAll(desired);
	}

	void clear(Consumer<Event> emit) {
		pairs.clear();
		marked.keySet().forEach(player -> emit.accept(new SpecificAutoMarkRequest(player, MarkerSign.CLEAR)));
		marked.clear();
	}
}
