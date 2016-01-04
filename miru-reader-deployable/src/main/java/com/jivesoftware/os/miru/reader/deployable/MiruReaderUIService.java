package com.jivesoftware.os.miru.reader.deployable;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.jivesoftware.os.miru.ui.MiruPageRegion;
import com.jivesoftware.os.miru.ui.MiruSoyRenderer;
import java.util.List;

/**
 *
 */
public class MiruReaderUIService {

    private final MiruSoyRenderer renderer;
    private final MiruHeaderRegion headerRegion;
    private final MiruAdminRegion adminRegion;
    private final MiruPageRegion<Optional<String>> partitionsRegion;
    private final MiruPageRegion<Void> errorsRegion;
    private final List<MiruReaderUIPlugin> plugins = Lists.newCopyOnWriteArrayList();

    public MiruReaderUIService(
        MiruSoyRenderer renderer,
        MiruHeaderRegion headerRegion,
        MiruAdminRegion adminRegion,
        MiruPageRegion<Optional<String>> partitionsRegion,
        MiruPageRegion<Void> errorsRegion) {
        this.renderer = renderer;
        this.headerRegion = headerRegion;
        this.adminRegion = adminRegion;
        this.partitionsRegion = partitionsRegion;
        this.errorsRegion = errorsRegion;
    }

    public void registerPlugin(MiruReaderUIPlugin plugin) {
        plugins.add(plugin);
    }

    private <I, R extends MiruPageRegion<I>> MiruChromeRegion<I, R> chrome(R region) {
        return new MiruChromeRegion<>("soy.miru.chrome.chromeRegion", renderer, headerRegion, plugins, region);
    }

    private <I, R extends MiruPageRegion<I>> MiruFrameRegion<I, R> frame(R region) {
        return new MiruFrameRegion<>("soy.miru.frame.chromeRegion", renderer, region);
    }

    public String render() {
        return chrome(adminRegion).render(null);
    }

    public String renderPartitions(Optional<String> input) {
        return chrome(partitionsRegion).render(input);
    }

    public String renderErrors() {
        return chrome(errorsRegion).render(null);
    }
}
