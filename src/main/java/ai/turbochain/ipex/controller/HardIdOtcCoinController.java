package ai.turbochain.ipex.controller;

import static ai.turbochain.ipex.util.MessageResult.success;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.querydsl.core.types.dsl.BooleanExpression;

import ai.turbochain.ipex.constant.PageModel;
import ai.turbochain.ipex.entity.OtcCoinSubscription;
import ai.turbochain.ipex.entity.QOtcCoinSubscription;
import ai.turbochain.ipex.service.OtcCoinSubscriptionService;
import ai.turbochain.ipex.util.MessageResult;
import lombok.extern.slf4j.Slf4j;

/**
 * @author 
 * @date 2019年12月18日
 */
@RestController
@Slf4j
@RequestMapping(value = "/hard-id/coin")
public class HardIdOtcCoinController {

    @Autowired
    private OtcCoinSubscriptionService otcCoinSubscriptionService;

 
    /**
     * 取得订阅的币种
     *
     * @return
     */
    @RequestMapping(value = "all")
    public MessageResult allCoin(PageModel pageModel) throws Exception {
    	BooleanExpression eq = QOtcCoinSubscription.otcCoinSubscription.origin.eq(2);
         
    	Page<OtcCoinSubscription> page = otcCoinSubscriptionService.findAll(eq, pageModel);
     
        MessageResult result = success();
        result.setData(page);
        return result;
    }
}
