/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.deploy.kubernetes

import java.io.File

import com.google.common.base.Charsets
import com.google.common.io.Files
import io.fabric8.kubernetes.client.{Config, ConfigBuilder, DefaultKubernetesClient, KubernetesClient}
import io.fabric8.kubernetes.client.utils.HttpClientUtils
import okhttp3.Dispatcher

import org.apache.spark.SparkConf
import org.apache.spark.deploy.kubernetes.config._
import org.apache.spark.util.ThreadUtils

/**
 * Spark-opinionated builder for Kubernetes clients. It uses a prefix plus common suffixes to
 * parse configuration keys, similar to the manner in which Spark's SecurityManager parses SSL
 * options for different components.
 */
private[spark] object SparkKubernetesClientFactory {

  def createKubernetesClient(
      master: String,
      namespace: Option[String],
      kubernetesAuthConfPrefix: String,
      sparkConf: SparkConf,
      maybeServiceAccountToken: Option[File],
      maybeServiceAccountCaCert: Option[File]): KubernetesClient = {
    val oauthTokenFileConf = s"$kubernetesAuthConfPrefix.$OAUTH_TOKEN_FILE_CONF_SUFFIX"
    val oauthTokenConf = s"$kubernetesAuthConfPrefix.$OAUTH_TOKEN_CONF_SUFFIX"
    val oauthTokenFile = sparkConf.getOption(oauthTokenFileConf)
      .map(new File(_))
      .orElse(maybeServiceAccountToken)
    val oauthTokenValue = sparkConf.getOption(oauthTokenConf)
    OptionRequirements.requireNandDefined(
        oauthTokenFile,
        oauthTokenValue,
        s"Cannot specify OAuth token through both a file $oauthTokenFileConf and a" +
            s" value $oauthTokenConf.")

    val trustStorePasswordConf = s"$kubernetesAuthConfPrefix.$TRUSTSTORE_PASSWORD_CONF_SUFFIX"
    val trustStorePasswordFileConf = s"$kubernetesAuthConfPrefix.$TRUSTSTORE_PASSWORD_FILE_CONF_SUFFIX"
    val trustStore = sparkConf
        .getOption(s"$kubernetesAuthConfPrefix.$TRUSTSTORE_CONF_SUFFIX")
    val trustStorePassword = sparkConf.getOption(trustStorePasswordConf)
    val trustStorePasswordFile = sparkConf.getOption(trustStorePasswordFileConf)
    OptionRequirements.requireNandDefined(
        trustStorePassword,
        trustStorePasswordFile,
        s"Cannot specify trustStore Password through both a value $trustStorePasswordConf and a" +
            s" file $trustStorePasswordFileConf")
    val resolvedTrustStorePassword = trustStorePassword.orElse(
        trustStorePasswordFile.map(f => Files.toString(new File(f), Charsets.UTF_8)))
    val caCertFile = sparkConf
        .getOption(s"$kubernetesAuthConfPrefix.$CA_CERT_FILE_CONF_SUFFIX")
        .orElse(maybeServiceAccountCaCert.map(_.getAbsolutePath))
    val clientKeyFile = sparkConf
        .getOption(s"$kubernetesAuthConfPrefix.$CLIENT_KEY_FILE_CONF_SUFFIX")
    val clientCertFile = sparkConf
        .getOption(s"$kubernetesAuthConfPrefix.$CLIENT_CERT_FILE_CONF_SUFFIX")
    val dispatcher = new Dispatcher(
        ThreadUtils.newDaemonCachedThreadPool("kubernetes-dispatcher"))
    val config = new ConfigBuilder()
        .withApiVersion("v1")
        .withMasterUrl(master)
        .withWebsocketPingInterval(0)
        .withOption(oauthTokenValue) {
          (token, configBuilder) => configBuilder.withOauthToken(token)
        }.withOption(oauthTokenFile) {
          (file, configBuilder) =>
              configBuilder.withOauthToken(Files.toString(file, Charsets.UTF_8))
        }.withOption(caCertFile) {
          (file, configBuilder) => configBuilder.withCaCertFile(file)
        }.withOption(clientKeyFile) {
          (file, configBuilder) => configBuilder.withClientKeyFile(file)
        }.withOption(clientCertFile) {
          (file, configBuilder) => configBuilder.withClientCertFile(file)
        }.withOption(namespace) {
          (ns, configBuilder) => configBuilder.withNamespace(ns)
        }.withOption(trustStore) {
          (trustStore, configBuilder) => configBuilder.withTrustStoreFile(trustStore)
        }.withOption(resolvedTrustStorePassword) {
          (pw, configBuilder) => configBuilder.withTrustStorePassphrase(pw)
        }.build()
    val baseHttpClient = HttpClientUtils.createHttpClient(config)
    val httpClientWithCustomDispatcher = baseHttpClient.newBuilder()
      .dispatcher(dispatcher)
      .build()
    new DefaultKubernetesClient(httpClientWithCustomDispatcher, config)
  }

  private implicit class OptionConfigurableConfigBuilder(configBuilder: ConfigBuilder) {

    def withOption[T]
        (option: Option[T])
        (configurator: ((T, ConfigBuilder) => ConfigBuilder)): OptionConfigurableConfigBuilder = {
      new OptionConfigurableConfigBuilder(option.map { opt =>
        configurator(opt, configBuilder)
      }.getOrElse(configBuilder))
    }

    def build(): Config = configBuilder.build()
  }
}
