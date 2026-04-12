package com.cloudsync.client.hostmodel;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Serdeable
public record IPhoneListDevicesResult(List<String> devices) {}
