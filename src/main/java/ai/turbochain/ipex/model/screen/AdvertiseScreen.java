package ai.turbochain.ipex.model.screen;

import com.querydsl.core.types.dsl.BooleanExpression;

import ai.turbochain.ipex.ability.ScreenAbility;
import ai.turbochain.ipex.constant.AdvertiseControlStatus;
import ai.turbochain.ipex.entity.QAdvertise;
import lombok.Data;

import java.util.ArrayList;

/**
 * @author GS
 * @Title: ${file_name}
 * @Description:
 * @date 2018/4/2711:45
 */
@Data
public class AdvertiseScreen implements ScreenAbility {

    AdvertiseControlStatus status;

    /**
     * 处理内部断言
     *
     * @return
     */
    @Override
    public ArrayList<BooleanExpression> getBooleanExpressions() {
        ArrayList<BooleanExpression> booleanExpressions = new ArrayList<>();
        if (status != null) {
            booleanExpressions.add(QAdvertise.advertise.status.eq(status));
        }
        return booleanExpressions;
    }


}
