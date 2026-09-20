package com.acme.hdfsperf;

import com.acme.hdfsperf.cli.Arguments;
import com.acme.hdfsperf.core.Inspector;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            if (parsed.help()) {
                System.out.println(Arguments.usage());
                return;
            }
            int code = new Inspector(parsed).run();
            if (code != 0) System.exit(code);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            System.err.println(Arguments.usage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("分析失败: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
