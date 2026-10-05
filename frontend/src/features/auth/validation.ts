export function validUsername(value: string) {
  return /^[A-Za-z0-9][A-Za-z0-9_.-]{2,63}$/.test(value);
}

export function validEmail(value: string) {
  if (
    value.length > 254 ||
    /\p{Cc}/u.test(value) ||
    !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)
  )
    return false;
  const [local, domain] = value.split("@");
  return (
    !local.startsWith(".") &&
    !local.endsWith(".") &&
    !local.includes("..") &&
    domain
      .split(".")
      .every(
        (part) => part !== "" && !part.startsWith("-") && !part.endsWith("-"),
      )
  );
}
