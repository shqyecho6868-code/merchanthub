package com.merchanthub.common.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;


@TableName(value ="`message`")
@Data
public class Message implements Serializable {
    /**
     * Message id
     */
    @TableId
    private Long messageId;

    /**
     * Sender id
     */
    private Long senderId;

    /**
     * Session id
     */
    private Long sessionId;

    /**
     * Message type: 0 text, 1 image, 2 sticker
     */
    private Integer type;

    /**
     * Message content
     */
    private String content;

    /**
     * Referenced (quoted) message id
     */
    private Long replyId;

    /**
     * Session type: 0 one-to-one, 1 group
     */
    private Integer sessionType;

    /**
     * Created at
     */
    private Date createdTime;

    /**
     * Updated at
     */
    private Date updatedTime;


    @TableField(exist = false)
    @Serial
    private static final long serialVersionUID = 1L;
}