package dev.videosearch.library;

import com.baomidou.mybatisplus.annotation.*;

@TableName("vs_library")
public class LibraryEntity {
  @TableId public String id;
  public String owner;
  public String name;
}
