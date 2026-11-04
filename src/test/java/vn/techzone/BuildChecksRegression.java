package vn.techzone;

import vn.techzone.backend.BuildChecks;
import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.util.*;

public class BuildChecksRegression {
    private static ProductVariant item(String id,String category,Map<String,String> specs) {
        return new ProductVariant(id,id,id,category,"brand","","",100,0,0,specs);
    }
    private static void check(boolean condition) { if(!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        var gpu=item("gpu","GPU",Map.of("gpuLengthMm","300","source.gpuLengthMm","https://example.com/gpu", "requiredPcieConnectors","1","source.requiredPcieConnectors","https://example.com/gpu"));
        var chassis=item("case","Case",Map.of("maxGpuLengthMm","290","source.maxGpuLengthMm","https://example.com/case"));
        var psu=item("psu","PSU",Map.of("pcieConnectors","2","source.pcieConnectors","https://example.com/psu"));
        var checks=BuildChecks.evaluate(List.of(gpu,chassis,psu));
        check(checks.stream().anyMatch(c->c.name().equals("Chiều dài GPU") && c.status().equals("fail")));
        check(checks.stream().anyMatch(c->c.name().equals("Cáp GPU PCIe") && c.status().equals("pass")));
        check(checks.stream().anyMatch(c->c.name().equals("BIOS") && c.status().equals("unknown")));
        var unsourced=item("case","Case",Map.of("maxGpuLengthMm","500"));
        check(BuildChecks.evaluate(List.of(gpu,unsourced)).stream().anyMatch(c->c.name().equals("Chiều dài GPU") && c.status().equals("unknown")));
        System.out.println("Build checks: sourced mismatch, connectors and unknown data passed");
    }
}
