package vn.techzone.application;

import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.ToDoubleBiFunction;

/** Runs contextual recommendation ranking after the caller has applied safety and stock filters. */
public final class RecommendationRankingService {
    public record Result(List<ProductVariant> items,String strategy,double inferenceMs,double totalMs) { }
    private record Ranked(List<ProductVariant> items,long inferenceNanos) { }

    public Result rank(String primaryId,List<ProductVariant> eligible,Set<String> context,int topK,
                       boolean serviceAvailable,boolean modelAvailable,boolean basketEvidence,
                       Map<String,Double> pretrainedScores,String model,Map<String,Integer> popularity,
                       ToDoubleBiFunction<ProductVariant,Set<String>> score,ThreadPoolExecutor executor) {
        long started=System.nanoTime();int limit=Math.max(1,Math.min(8,topK));String fallback=null;
        try {
            if(!serviceAvailable)fallback="Fallback phổ biến · dịch vụ không sẵn sàng";
            else if(!modelAvailable)fallback="Fallback phổ biến · thiếu mô hình";
            else if(!basketEvidence&&pretrainedScores==null)fallback="Fallback phổ biến · sản phẩm mới";
            else if(context.stream().noneMatch(id->!id.equals(primaryId))&&pretrainedScores==null)fallback="Fallback phổ biến · chưa có ngữ cảnh";
        } catch(RuntimeException ex){fallback="Fallback phổ biến · lỗi mô hình";}
        if(fallback==null&&!eligible.isEmpty()) {
            Future<Ranked> future=null;
            try {
                Set<String> scoringContext=Set.copyOf(context);Map<String,Double> scores=pretrainedScores;
                future=executor.submit(()->{
                    long inferenceStart=System.nanoTime();
                    List<ProductVariant> items=eligible.stream().sorted(Comparator
                            .comparingDouble((ProductVariant p)->score.applyAsDouble(p,scoringContext)
                                    +(scores==null?0:Math.max(-1,Math.min(1,scores.getOrDefault(p.id,0.0)))))
                            .reversed().thenComparing(p->p.id)).limit(limit).toList();
                    return new Ranked(items,System.nanoTime()-inferenceStart);
                });
                Ranked result=future.get(90,TimeUnit.MILLISECONDS);
                boolean contextual=context.stream().anyMatch(id->!id.equals(primaryId));
                String strategy=contextual?"AI gợi ý · giỏ hàng và ngữ cảnh phiên":"AI gợi ý · tương đồng ngữ nghĩa";
                if(pretrainedScores!=null)strategy+=" · "+Objects.toString(model,"");
                return new Result(result.items(),strategy,result.inferenceNanos()/1_000_000.0,(System.nanoTime()-started)/1_000_000.0);
            } catch(InterruptedException ex){Thread.currentThread().interrupt();fallback="Fallback phổ biến · tác vụ bị gián đoạn";}
            catch(TimeoutException ex){fallback="Fallback phổ biến · quá thời gian suy luận";}
            catch(ExecutionException|RejectedExecutionException ex){fallback="Fallback phổ biến · lỗi mô hình";}
            finally {if(future!=null&&!future.isDone())future.cancel(true);executor.purge();}
        }
        if(fallback==null)fallback="AI gợi ý · không có ứng viên an toàn";
        List<ProductVariant> items=eligible.stream().sorted(Comparator
                .comparingInt((ProductVariant p)->popularity.getOrDefault(p.id,0)).reversed().thenComparing(p->p.id)).limit(limit).toList();
        return new Result(items,fallback,0,(System.nanoTime()-started)/1_000_000.0);
    }
}
