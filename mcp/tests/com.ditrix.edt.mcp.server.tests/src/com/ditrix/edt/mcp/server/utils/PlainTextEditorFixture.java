package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Field;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.ide.IEditorAssociationOverride;

/** Scoped to disposable UI fixtures; real EDT semantic editors need the full product runtime. */
public final class PlainTextEditorFixture implements AutoCloseable
{
    private final Field overrides;
    private final Object previous;

    public PlainTextEditorFixture() throws ReflectiveOperationException
    {
        // EDT's BSL override ignores even IDE.setDefaultEditor(file, ...).
        // IDE exposes no public way to suspend these overrides; restore its cache after each fixture.
        overrides = IDE.class.getDeclaredField("editorAssociationOverrides");
        overrides.setAccessible(true);
        previous = overrides.get(null);
        overrides.set(null, new IEditorAssociationOverride[0]);
    }

    @Override public void close() throws IllegalAccessException
    {
        overrides.set(null, previous);
    }
}
