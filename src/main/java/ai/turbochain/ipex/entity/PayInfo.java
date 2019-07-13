package ai.turbochain.ipex.entity;

import ai.turbochain.ipex.entity.Alipay;
import ai.turbochain.ipex.entity.BankInfo;
import ai.turbochain.ipex.entity.WechatPay;
import lombok.Builder;
import lombok.Data;

/**
 * @author GS
 * @date 2018年01月20日
 */
@Builder
@Data
public class PayInfo {
    private String realName;
    private Alipay alipay;
    private WechatPay wechatPay;
    private BankInfo bankInfo;
}
