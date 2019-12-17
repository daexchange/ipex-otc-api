package ai.turbochain.ipex.entity;

import lombok.Data;

/**
 * @author 未央
 * @create 2019-12-17 11:34
 */
@Data
public class RespDetail {

    private OrderDetail orderDetail;

    private Member publisher;

    private Member customer;

}
