/** MCP Server for EDT. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.utils.compare;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;

import com._1c.g5.v8.dt.bsl.compare.BslModuleSectionComparisonNode;
import com._1c.g5.v8.dt.bsl.compare.BslModuleSectionType;
import com._1c.g5.v8.dt.bsl.compare.ComparisonTextRegion;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;

/** Snapshot only the native section's ranges while the comparison read boundary is held. */
final class ComparisonSectionText
{
    private ComparisonSectionText()
    {
    }

    static ComparisonTextDiff.Source capture(ComparisonTextDiff.Source module,
        BslModuleSectionComparisonNode section, ComparisonSide side)
    {
        if (module.problem() != null || module.absent())
            return new ComparisonTextDiff.Source(module.module(), module.text(), module.absent(),
                module.problem(), List.of());
        if (section.getSymlink(side) == null)
            return new ComparisonTextDiff.Source(module.module(), "", true, null, List.of());
        try
        {
            List<ComparisonTextRegion> ranges = new ArrayList<>(section.getComparisonRegions());
            // Native unsplit methods already cover signature, documentation and body. Do not
            // duplicate them by also reading child ranges. Use signature/body children only
            // when the method itself supplies no ranges.
            if (ranges.isEmpty() && (section.getSectionType() == BslModuleSectionType.PROCEDURE
                || section.getSectionType() == BslModuleSectionType.FUNCTION))
            {
                for (BslModuleSectionComparisonNode child : section.getChildren())
                    if (child.getSectionType() == BslModuleSectionType.METHOD_SIGNATURE
                        || child.getSectionType() == BslModuleSectionType.METHOD_BODY)
                        ranges.addAll(child.getComparisonRegions());
            }
            if (ranges.isEmpty()) throw new IllegalArgumentException("Section source ranges are unavailable");
            // Both snapshots use the same slot order, including slots absent on one side.
            ranges.sort(Comparator.comparingInt(ComparisonSectionText::pairedOffset));
            Document document = new Document(module.text());
            List<ComparisonTextDiff.Fragment> fragments = new ArrayList<>();
            for (ComparisonTextRegion pair : ranges)
            {
                var range = pair.getRegion(side);
                if (range == null)
                {
                    fragments.add(ComparisonTextDiff.Fragment.absent());
                    continue;
                }
                int offset = range.getOffset(), length = range.getLength();
                if (offset < 0 || length < 0 || offset > document.getLength()
                    || length > document.getLength() - offset)
                    throw new IllegalArgumentException("Invalid section source range");
                int line = document.getLineOfOffset(offset);
                fragments.add(new ComparisonTextDiff.Fragment(document.get(offset, length), offset,
                    line + 1, offset - document.getLineOffset(line) + 1));
            }
            var present = fragments.stream().filter(fragment -> fragment.offset() >= 0)
                .sorted(Comparator.comparingInt(ComparisonTextDiff.Fragment::offset)).toList();
            if (present.isEmpty()) throw new IllegalArgumentException("Present section has no source ranges on this side");
            int end = 0;
            for (var fragment : present)
            {
                if (fragment.offset() < end) throw new IllegalArgumentException("Overlapping section source ranges");
                end = fragment.offset() + fragment.text().length();
            }
            StringBuilder text = new StringBuilder();
            fragments.forEach(fragment -> text.append(fragment.text()));
            return new ComparisonTextDiff.Source(module.module(), text.toString(), false, null, fragments);
        }
        catch (BadLocationException | RuntimeException e)
        {
            return new ComparisonTextDiff.Source(module.module(), null, false,
                "Could not read section fragments: " + ComparisonFailures.describe(e), List.of());
        }
    }

    private static int pairedOffset(ComparisonTextRegion pair)
    {
        for (var side : new ComparisonSide[] {ComparisonSide.MAIN, ComparisonSide.OTHER, ComparisonSide.COMMON_ANCESTOR})
            if (pair.getRegion(side) != null) return pair.getRegion(side).getOffset();
        return 0;
    }
}
