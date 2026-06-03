package com.truthscope.web.adapter.datasource;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.List;

/** data.go.kr XML 응답 파싱용 패키지-프라이빗 루트 DTO. 중첩 클래스로 header/body/NewsItem을 포함한다. */
@JacksonXmlRootElement(localName = "response")
class PolicyResponse {

  @JacksonXmlProperty(localName = "header")
  public ResponseHeader header;

  @JacksonXmlProperty(localName = "body")
  public ResponseBody body;

  static class ResponseHeader {

    @JacksonXmlProperty(localName = "resultCode")
    public String resultCode;

    @JacksonXmlProperty(localName = "resultMsg")
    public String resultMsg;
  }

  static class ResponseBody {

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "NewsItem")
    public List<NewsItem> newsItems;
  }

  static class NewsItem {

    @JacksonXmlProperty(localName = "Title")
    public String title;

    @JacksonXmlProperty(localName = "SubTitle1")
    public String subTitle1;

    @JacksonXmlProperty(localName = "DataContents")
    public String dataContents;

    @JacksonXmlProperty(localName = "MinisterCode")
    public String ministerCode;

    @JacksonXmlProperty(localName = "OriginalUrl")
    public String originalUrl;

    @JacksonXmlProperty(localName = "ApproveDate")
    public String approveDate;

    // 보도자료 전용 첨부파일 (0..n) — 파싱은 하되 DataGoKrPolicyItem에는 포함 안 함
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "FileName")
    public List<String> fileNames;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "FileUrl")
    public List<String> fileUrls;
  }
}
