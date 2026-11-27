package io.github.letsopen.localoss.tools;

import io.github.letsopen.localoss.config.SystemProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 命令行工具入口：
 * <pre>
 *   java -jar local-oss.jar --localoss.backfill-meta [--localoss.bucket=x] [--localoss.dry-run] [--localoss.concurrency=8]
 * </pre>
 */
@Component
public class MetaBackfillRunner implements ApplicationRunner {

    private final SystemProperties props;

    public MetaBackfillRunner(SystemProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("localoss.backfill-meta")) {
            return;
        }
        String bucket = args.getOptionValues("localoss.bucket") == null
                ? null : args.getOptionValues("localoss.bucket").get(0);
        boolean dryRun = args.containsOption("localoss.dry-run");
        int concurrency = args.getOptionValues("localoss.concurrency") == null
                ? 8 : Integer.parseInt(args.getOptionValues("localoss.concurrency").get(0));

        System.out.println("[MetaBackfill] start bucket=" + (bucket == null ? "(all)" : bucket)
                + " dryRun=" + dryRun + " concurrency=" + concurrency);
        try {
            MetaBackfillTool.Result r = MetaBackfillTool.run(props, bucket, dryRun, concurrency);
            System.out.println("[MetaBackfill] done processed=" + r.processed()
                    + " skipped=" + r.skipped() + (r.dryRun() ? " (dry run)" : ""));
        } catch (Exception e) {
            System.err.println("[MetaBackfill] failed: " + e.getMessage());
            System.exit(1);
        }
        System.exit(0);
    }
}
