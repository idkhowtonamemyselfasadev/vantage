package dev.vantage.core.command;

import java.util.List;

/** The modules a user can name in a command (locked betas are not among them). */
public interface ModuleSource {
    List<? extends ModuleRef> modules();
}
