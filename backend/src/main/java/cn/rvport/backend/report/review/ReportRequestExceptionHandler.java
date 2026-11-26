package cn.rvport.backend.report.review;

import cn.rvport.backend.common.error.ApiErrorResponse;
import cn.rvport.backend.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 在 JSON 绑定边界拒绝非法报告请求，不改变其他业务接口的错误协议。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ReportReviewGenerationController.class)
public class ReportRequestExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> unreadable(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse("REPORT_REQUEST_INVALID",
                "报告生成请求不正确，请检查字段名称、类型及 JSON 格式",
                Map.of("messageEn", "Invalid report request; check field names, types and JSON syntax"),
                TraceIdFilter.currentTraceId(request), Instant.now()));
    }
}
