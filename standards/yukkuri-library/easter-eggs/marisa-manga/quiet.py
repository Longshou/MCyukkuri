"""Quiet mode for the headless Blender entries: agent building and rendering yield to the desktop and the user's game.

Called by make.py and render_clips.py as they start; `scripts/quiet_mode.py` switches it on for the whole machine.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(HERE))))


def apply():
    """Below normal priority, idle on the quiet-mode CPUs, and rendering capped to those CPUs while quiet mode is on."""
    sys.path.insert(0, os.path.join(ROOT, 'scripts'))
    import headless_server
    headless_server.below_normal_priority()
    cpus = headless_server.quiet_cpus()
    if cpus:
        import bpy
        scene = bpy.context.scene
        scene.render.threads_mode = 'FIXED'
        scene.render.threads = len(cpus)
