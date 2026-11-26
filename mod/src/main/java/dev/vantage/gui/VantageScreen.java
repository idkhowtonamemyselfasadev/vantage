package dev.vantage.gui;

/** A Vantage screen does what its buttons do through one method, so the rig can press them without a mouse. */
public interface VantageScreen {
    void act(String id);
}
