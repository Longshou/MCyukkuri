package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import java.util.Set;

/** One way to reach an {@link Intent}. A method that is applicable shrinks the reasons it declares, so a blocked step switches to a method whose {@link #reduces()} holds it. */
public interface Method {
    String id();

    /** Pure: may this body use this method now? No side effects. */
    Feasibility applicable();

    /** The reasons this method can shrink; a method that reduces nothing is only the last resort. */
    Set<Reason> reduces();

    /** The next step, or null when the method has nothing left while the intent is still unsatisfied (NO_PROGRESS). */
    Step next();
}
